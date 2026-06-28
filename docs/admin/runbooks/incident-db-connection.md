# Ранбук: Инцидент — потеря соединения с БД

**Уровень:** Critical — при потере БД система не принимает новые ACARS-сообщения и не обрабатывает очереди.

---

## Симптомы

- Readiness probe возвращает HTTP 503 (`{"status":"DOWN"}`)
- В логах: `HikariPool-1 - Connection is not available, request timed out after Xms`
- В логах: `PSQLException: FATAL: connection refused` или `could not connect to server`
- HTTP 503 на всех запросах API (pod выведен из ротации Service)
- Метрика `hikaricp_connections_active` = 0, `hikaricp_connections_pending` > 0
- Планировщики (WAIT-таймауты, outbound delivery) перестают тикать
- Event Log не пополняется

---

## Диагностика

### Шаг 1. Подтвердить симптомы

```bash
# Статус readiness всех pod'ов
kubectl get pods -n eca
# Ожидаемый аномальный вывод: READY 0/1

# Проверить readiness детально
kubectl port-forward -n eca <POD_NAME> 8081:8080 &
curl -sv http://localhost:8081/actuator/health/readiness
# {"status":"DOWN","components":{"db":{"status":"DOWN"},...}}
```

### Шаг 2. Проверить доступность PostgreSQL

```bash
# Прямая проверка соединения с хоста (или из pod'а)
kubectl exec -n eca <BACKEND_POD> -- \
  sh -c 'pg_isready -h $DB_HOST -p 5432 -U $DB_USER -d $DB_NAME 2>&1 || echo UNREACHABLE'

# Проверить DNS-резолюцию хоста БД
kubectl exec -n eca <BACKEND_POD> -- nslookup prod-postgres.internal

# Проверить TCP-соединение
kubectl exec -n eca <BACKEND_POD> -- \
  sh -c 'nc -zv prod-postgres.internal 5432 && echo "TCP OK" || echo "TCP FAILED"'
```

### Шаг 3. Проверить HikariCP через метрики

```bash
kubectl port-forward -n eca <BACKEND_POD> 8081:8080 &
curl -s http://localhost:8081/actuator/prometheus | grep hikaricp

# Ключевые значения:
# hikaricp_connections_active    — активные соединения (должно быть > 0 при норме)
# hikaricp_connections_pending   — ожидающие соединения (> 0 = bottleneck или недоступность БД)
# hikaricp_connections_timeout_total — таймауты получения соединения (рост = проблема)
```

### Шаг 4. Проверить состояние PostgreSQL

```bash
# Из pod'а backend (если TCP-соединение есть)
kubectl exec -n eca <BACKEND_POD> -- \
  sh -c 'psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "SELECT version();"'

# Проверить pg_stat_activity (если доступен)
psql -h $DB_HOST -U postgres -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"

# Проверить лимит соединений
psql -h $DB_HOST -U postgres -c \
  "SELECT max_conn, used FROM
   (SELECT setting::int AS max_conn FROM pg_settings WHERE name='max_connections') mc,
   (SELECT count(*) AS used FROM pg_stat_activity) ua;"
```

### Шаг 5. Проверить логи PostgreSQL

```bash
# Kubernetes StatefulSet (dev/test)
kubectl logs -n eca eca-system-postgres-0 --tail=50

# Внешний PostgreSQL (prod) — через SSH на хост БД
journalctl -u postgresql -n 50 --no-pager
# или
tail -n 100 /var/log/postgresql/postgresql-$(date +%Y-%m-%d).log
```

---

## Ответные меры

### Сценарий A: PostgreSQL временно недоступен (сеть, перезагрузка)

Никаких действий на стороне приложения не требуется. HikariCP автоматически переподключается при восстановлении БД. Readiness probe вернётся в `UP` на следующем опросе (в пределах `periodSeconds: 10`).

Убедиться в восстановлении:

```bash
# Дождаться UP readiness
watch -n 5 "kubectl get pods -n eca"
# Или
kubectl wait --for=condition=Ready pod -l app.kubernetes.io/component=backend -n eca --timeout=120s
```

### Сценарий B: Исчерпан пул соединений PostgreSQL (max_connections)

Симптом: `psql` подключается, но `pg_isready` OK, при этом приложение не может получить соединение.

```bash
# Проверить pg_stat_activity
psql -h $DB_HOST -U postgres -c \
  "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'eca-hikari';"

# Если занято много соединений «зависшими» сессиями — завершить их
psql -h $DB_HOST -U postgres -c \
  "SELECT pg_terminate_backend(pid)
   FROM pg_stat_activity
   WHERE application_name = 'eca-hikari'
     AND state = 'idle'
     AND state_change < NOW() - INTERVAL '10 minutes';"
```

После этого HikariCP восстановит пул автоматически.

Долгосрочно: уменьшить `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` или увеличить `max_connections` PostgreSQL (требует рестарта PostgreSQL).

### Сценарий C: Failover на реплику PostgreSQL

При потере primary-узла PostgreSQL (prod) необходимо переключиться на standby-реплику. Детали зависят от инфраструктуры (Patroni, repmgr, ручной failover).

```bash
# 1. Убедиться, что standby-реплика promote
psql -h STANDBY_HOST -U postgres -c "SELECT pg_is_in_recovery();"
# Ожидаемый ответ: f (false — т.е. уже primary)

# 2. Обновить Secret Kubernetes с новым DB_HOST
kubectl create secret generic eca-system-db \
  --namespace eca \
  --from-literal=url='jdbc:postgresql://STANDBY_HOST:5432/eca_db' \
  --from-literal=username='eca_user' \
  --from-literal=password='PROD_DB_PASSWORD' \
  --dry-run=client -o yaml | kubectl apply -f -

# 3. Перезапустить pod'ы для подхвата нового Secret
kubectl rollout restart deployment/eca-system-backend -n eca
kubectl rollout status deployment/eca-system-backend -n eca
```

---

## Что ломается при потере БД более grace period

При потере БД дольше времени graceful shutdown (25 с для in-flight запросов, 40 с `terminationGracePeriodSeconds`):

| Подсистема | Последствие |
|-----------|------------|
| Входящие ACARS | 503 на `/api/acars/**`, сообщения теряются (нет буфера без БД) |
| Движок ECA | Новые события не обрабатываются; текущие RUNNING-экземпляры «заморожены» |
| Outbound messages | Доставка остановлена; сообщения в очереди PENDING не теряются (хранятся в `outbound_messages`) |
| WAIT FOR steps | Таймауты не тикают (планировщик остановлен); после восстановления — продолжат с того же шага |
| Event Log | Не пополняется |
| Leader election | При потере БД TTL истекает; после восстановления лидерство перехватывается первой репликой |
| Пользователи | Выход из системы; refresh-токены не валидируются (требуют БД) |

После восстановления БД система автоматически возобновляет работу. Проверить `outbound_messages` на статус `FAILED` (если delivery-таймаут истёк при недоступности канала).

---

## Профилактика

- Настроить alert на `hikaricp_connections_pending > 0` сроком > 2 минуты.
- Настроить alert на `up{job="eca-system"} == 0` (readiness DOWN).
- В prod использовать managed PostgreSQL с автоматическим failover (Patroni/repmgr).
- Регулярно проверять `pg_stat_activity` на накопление «зависших» соединений.
- Значение `max_connections` PostgreSQL должно быть > `N_replicas × DB_POOL_MAX + 10` (запас для admin-сессий).
