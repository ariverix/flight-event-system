# Ранбук: Leader Election завис (нет лидера)

**Уровень:** Warning — планировщики ECA не тикают; WAIT FOR таймауты и доставка исходящих сообщений остановлены.

---

## Содержание

1. [Признаки проблемы](#признаки-проблемы)
2. [Причины](#причины)
3. [Диагностика](#диагностика)
4. [Исправление](#исправление)
5. [Профилактика](#профилактика)

---

## Признаки проблемы

- В логах всех реплик: `[LeaderElectionService] Not a leader, skipping scheduled task` (без появления `Acquired leadership`)
- Экземпляры последовательностей со статусом `RUNNING` и шагом `WAIT FOR` не переходят дальше, даже если таймаут истёк
- Исходящие сообщения (`outbound_messages`) накапливаются в статусе `PENDING` без доставки
- В таблице `leader_election`: нет строки или строка есть, но `lease_until` в прошлом (`lease_until < NOW()`)
- Метрики: `eca.execution.event.duration` перестаёт обновляться

---

## Причины

### Причина 1: pod-лидер «грязно» завис без @PreDestroy

При нормальной остановке лидер выполняет `@PreDestroy` и удаляет строку в `leader_election` — другие реплики немедленно перехватывают лидерство. При аварийном падении (OOMKill, SIGKILL, node failure) строка остаётся с устаревшим `lease_until`. После истечения TTL (~30 с) лидерство должно автоматически перехватиться. Если этого не происходит — возможны причины 2–4.

### Причина 2: Все реплики потеряли соединение с БД одновременно

Без соединения с БД нельзя ни продлить аренду, ни перехватить лидерство. Симптомы включают ошибки HikariCP. Решение: восстановить соединение с БД — лидерство восстановится автоматически. См. ранбук [incident-db-connection.md](incident-db-connection.md).

### Причина 3: Строка leader_election отсутствует (первый запуск или ручное удаление)

При первом запуске строка создаётся автоматически (heartbeat-процесс каждые 10 с). Если удалена вручную — аналогично, восстановится при следующем heartbeat.

### Причина 4: Некорректный POD_NAME (все реплики видят одно имя)

Если `POD_NAME` не инжектируется через `fieldRef` и задан фиксированным значением — несколько реплик конкурируют с одним `holder_id`. Проверить конфигурацию Deployment.

---

## Диагностика

### Шаг 1. Проверить таблицу leader_election

```sql
SELECT
  lock_name,
  holder_id,
  lease_until,
  NOW() AS current_time,
  EXTRACT(EPOCH FROM (lease_until - NOW())) AS ttl_seconds,
  CASE
    WHEN lease_until > NOW() THEN 'ACTIVE'
    ELSE 'EXPIRED (no leader)'
  END AS status
FROM leader_election;
```

Интерпретация:
- `ttl_seconds > 0` — лидер активен, проблема в другом.
- `ttl_seconds < 0` — аренда истекла. Строка должна быть перехвачена репликой в течение следующих 10 с.
- Строки нет — лидер ещё не выбран (первый запуск или ручное удаление).

### Шаг 2. Проверить логи реплик

```bash
# Искать сообщения об election в логах всех pod'ов
kubectl logs -n eca -l app.kubernetes.io/component=backend --tail=100 \
  | grep -i "leader\|election\|acquired\|released\|skipping"

# Нормальная работа выглядит так:
# Acquired leadership (holder_id=eca-system-backend-xxx-yyy)
# [не-лидер] Not a leader, skipping scheduled task  ← это нормально для не-лидеров

# Проблема выглядит так (на ВСЕХ репликах):
# Not a leader, skipping scheduled task
# LeaderElection heartbeat failed: HikariPool timeout...
```

### Шаг 3. Проверить POD_NAME каждого pod'а

```bash
# Убедиться, что у каждого pod'а уникальный POD_NAME
for pod in $(kubectl get pods -n eca -l app.kubernetes.io/component=backend -o name); do
  echo -n "$pod → POD_NAME="; \
  kubectl exec -n eca $pod -- sh -c 'echo $POD_NAME'
done

# Все значения должны быть уникальными именами pod'ов
```

### Шаг 4. Проверить доступность БД из pod'ов

```bash
kubectl exec -n eca <POD_NAME> -- \
  sh -c 'pg_isready -h $DB_HOST -p 5432 2>&1'
```

---

## Исправление

### Метод 1: Принудительный сброс TTL (рекомендуется)

Если строка существует, но `lease_until` в будущем (лидер завис, не освободил аренду) — немедленно «протухить» аренду:

```sql
-- Принудительно завершить текущую аренду
UPDATE leader_election
SET lease_until = NOW() - INTERVAL '1 second'
WHERE lock_name = 'scheduler';

-- Проверить результат
SELECT lock_name, holder_id, lease_until,
       EXTRACT(EPOCH FROM (lease_until - NOW())) AS ttl_seconds
FROM leader_election;
-- ttl_seconds должен быть отрицательным
```

После этого в течение 10–15 секунд одна из живых реплик выполнит heartbeat и перехватит лидерство:

```bash
# Наблюдать за логами
kubectl logs -n eca -l app.kubernetes.io/component=backend -f \
  | grep -i "leader\|election\|acquired"
# Ожидаемо: "Acquired leadership (holder_id=eca-system-backend-xxx-yyy)"
```

### Метод 2: Удалить строку (если holder_id ссылается на несуществующий pod)

```sql
-- Удалить «мёртвую» строку
DELETE FROM leader_election WHERE lock_name = 'scheduler';
-- Строка будет пересоздана при следующем heartbeat живой реплики
```

### Метод 3: Перезапуск pod'ов (последний резерв)

Если методы 1 и 2 не помогли (все реплики не могут подключиться к БД):

```bash
kubectl rollout restart deployment/eca-system-backend -n eca
kubectl rollout status deployment/eca-system-backend -n eca
```

После рестарта при живой БД лидерство восстановится автоматически.

---

## Проверка восстановления

```bash
# 1. Убедиться, что в leader_election есть активная строка
psql -h $DB_HOST -U eca_user -d eca_db \
  -c "SELECT holder_id, lease_until, EXTRACT(EPOCH FROM (lease_until - NOW())) AS ttl_sec FROM leader_election;"
# ttl_sec > 0

# 2. В логах должно появиться "Acquired leadership"
kubectl logs -n eca -l app.kubernetes.io/component=backend --tail=50 | grep "Acquired"

# 3. Проверить, что outbound messages снова доставляются
psql -h $DB_HOST -U eca_user -d eca_db \
  -c "SELECT status, count(*) FROM outbound_messages GROUP BY status;"
# PENDING должен уменьшаться; SENT — расти

# 4. Проверить WAIT FOR таймауты — если были застрявшие экземпляры,
#    они должны начать продвигаться
psql -h $DB_HOST -U eca_user -d eca_db \
  -c "SELECT id, status, current_step_index, updated_at FROM execution_instances WHERE status = 'RUNNING';"
```

---

## Профилактика

- **Мониторинг:** настроить alert `leader_election.lease_until < NOW()` (запрос к БД каждые 30 с через экспортер или custom health check).
- **Graceful shutdown:** гарантировать `terminationGracePeriodSeconds: 40` в Deployment, чтобы `@PreDestroy` успел выполниться и освободить аренду.
- **POD_NAME через fieldRef:** не задавать фиксированным значением — только через `fieldRef.fieldPath: metadata.name`.
- **Не убивать pod'ы через `--grace-period=0`** без необходимости — это исключает `@PreDestroy` и оставляет «мёртвую» аренду.
- **TTL проверять при масштабировании:** после `kubectl scale --replicas=0` убедиться, что строка в `leader_election` удалена или истекла до восстановления реплик.
