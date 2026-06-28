# Руководство по эксплуатации ECA System

## Содержание

1. [Запуск и остановка](#запуск-и-остановка)
2. [Rolling update (обновление без downtime)](#rolling-update)
3. [Масштабирование](#масштабирование)
4. [Управление пользователями](#управление-пользователями)
5. [Мониторинг и ключевые метрики](#мониторинг-и-ключевые-метрики)
6. [Event Log (Tracking)](#event-log-tracking)
7. [Логи и трассировка](#логи-и-трассировка)

---

## Запуск и остановка

### Docker Compose

```bash
# Запуск
docker compose up -d

# Остановка (graceful, данные сохраняются)
docker compose down

# Перезапуск одного сервиса
docker compose restart app

# Просмотр статуса
docker compose ps
docker compose logs -f app
```

### Kubernetes

```bash
# Статус всех компонентов ECA
kubectl get all -n eca

# Остановка: уменьшить реплики до 0
kubectl scale deployment eca-system-backend --replicas=0 -n eca
kubectl scale deployment eca-system-frontend --replicas=0 -n eca

# Запуск: восстановить количество реплик
kubectl scale deployment eca-system-backend --replicas=3 -n eca
kubectl scale deployment eca-system-frontend --replicas=2 -n eca

# Graceful drain одного узла (плановое обслуживание)
kubectl drain <NODE_NAME> --ignore-daemonsets --delete-emptydir-data
# После обслуживания — вернуть узел
kubectl uncordon <NODE_NAME>
```

---

## Rolling Update

Rolling update обновляет pod'ы по одному без остановки сервиса. Параметр `maxUnavailable: 0` в Deployment гарантирует, что в любой момент доступно не менее `replicaCount` pod'ов.

### Обновление через Helm

```bash
# Обновить образ и/или параметры
helm upgrade eca deploy/helm/eca-system \
  --namespace eca \
  -f deploy/helm/eca-system/values-prod.yaml \
  --set backend.image.tag=v1.2.0 \
  --set externalDatabase.password='PROD_DB_PASSWORD' \
  --set jwt.secret='PROD_JWT_SECRET'

# Наблюдать за ходом обновления
kubectl rollout status deployment/eca-system-backend -n eca
```

### Откат при ошибке

```bash
# Откат к предыдущей версии Helm release
helm rollback eca -n eca

# Или явно указать ревизию
helm history eca -n eca           # посмотреть историю
helm rollback eca 3 -n eca        # откатиться к ревизии 3

# Убедиться, что откат применился
kubectl rollout status deployment/eca-system-backend -n eca
```

### Обратно несовместимые миграции БД

При Flyway-миграции, которая ломает совместимость со старой версией кода, применять шаблон expand/contract:

1. Деплоить backward-compatible миграцию отдельно (без изменения кода).
2. Убедиться, что все реплики старой версии работают.
3. Деплоить новую версию кода.
4. После перехода всех реплик — деплоить contract-миграцию (убрать deprecated-столбцы).

---

## Масштабирование

### Ручное масштабирование

```bash
# Увеличить количество реплик backend
kubectl scale deployment eca-system-backend --replicas=5 -n eca

# Проверить состояние
kubectl get pods -n eca -l app.kubernetes.io/component=backend
```

Leader election автоматически адаптируется: при добавлении реплик лидер продолжает работу, новые реплики остаются в режиме ожидания.

### Автомасштабирование (HPA)

HPA включён по умолчанию и масштабирует backend по CPU. Просмотр состояния:

```bash
kubectl get hpa -n eca
# NAME                    REFERENCE                         TARGETS   MINPODS   MAXPODS   REPLICAS
# eca-system-backend-hpa  Deployment/eca-system-backend     45%/70%   3         12        3

# Подробнее
kubectl describe hpa eca-system-backend-hpa -n eca
```

Изменение порогов HPA без пересборки:

```bash
helm upgrade eca deploy/helm/eca-system \
  --namespace eca \
  --reuse-values \
  --set hpa.targetCPUUtilizationPercentage=60 \
  --set hpa.maxReplicas=16
```

---

## Управление пользователями

### Роли

| Роль | Права |
|------|-------|
| `ADMIN` | Полный доступ: управление пользователями, последовательностями, системными настройками |
| `OPERATOR` | Просмотр и управление последовательностями, экземплярами, Event Log; без управления пользователями |

### API управления пользователями

Все запросы требуют токен роли `ADMIN`.

```bash
# Получить токен
TOKEN=$(curl -s -X POST http://eca.example.ru/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ADMIN_PASSWORD"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Создать оператора
curl -s -X POST http://eca.example.ru/api/users \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "username": "operator1",
    "password": "SecurePassword123!",
    "fullName": "Иван Иванов",
    "role": "OPERATOR"
  }' | python3 -m json.tool

# Получить список пользователей
curl -s -H "Authorization: Bearer $TOKEN" \
  http://eca.example.ru/api/users | python3 -m json.tool

# Заблокировать пользователя (id=2)
curl -s -X PATCH http://eca.example.ru/api/users/2/enabled \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled": false}'

# Разблокировать пользователя
curl -s -X PATCH http://eca.example.ru/api/users/2/enabled \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled": true}'
```

### Сброс пароля

```bash
curl -s -X PATCH http://eca.example.ru/api/users/2 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"password": "NewPassword456!"}'
```

---

## Мониторинг и ключевые метрики

### Эндпоинты health

| URL | Назначение | Аутентификация |
|-----|-----------|:---:|
| `/actuator/health/liveness` | JVM жива | Нет (k8s probe) |
| `/actuator/health/readiness` | БД + каналы | Нет (k8s probe) |
| `/actuator/health/startup` | Flyway готов | Нет (k8s probe) |
| `/actuator/health` | Полный health | ADMIN |
| `/actuator/prometheus` | Метрики Prometheus | ADMIN (или внутри кластера) |

В prod actuator закрыт публично. Доступ через port-forward:

```bash
kubectl port-forward -n eca deployment/eca-system-backend 9090:8080
curl -s http://localhost:9090/actuator/prometheus | grep eca_
```

### Ключевые метрики Prometheus

| Метрика | Описание | Норма |
|---------|---------|-------|
| `jvm_memory_used_bytes{area="heap"}` | Используемая heap JVM | < 80% от limit |
| `jvm_gc_pause_seconds_sum` | Суммарное время GC-пауз | < 5% от времени работы |
| `http_server_requests_seconds_count` | Число HTTP-запросов | Рост пропорционально нагрузке |
| `http_server_requests_seconds_max` | Максимальная задержка запроса | < 1 с (p99) |
| `eca_execution_event_duration_seconds` | Время обработки ACARS-события | < 100 мс (p95) |
| `hikaricp_connections_active` | Активные соединения HikariCP | < 80% от `maximum-pool-size` |
| `hikaricp_connections_pending` | Ожидающие соединения | Должно быть 0 при норме |
| `process_cpu_usage` | Загрузка CPU процессом JVM | < 0.7 |

### Метрика leader election

```promql
# Проверить, кто является текущим лидером (через логи или прямой запрос к БД)
```

```sql
-- Текущий лидер
SELECT holder_id, lock_name,
       lease_until,
       EXTRACT(EPOCH FROM (lease_until - NOW())) AS ttl_seconds
FROM leader_election
WHERE lock_name = 'scheduler';
```

Если `ttl_seconds` < 0 — лидера нет, планировщики не работают. Действия: см. ранбук [leader-election-stuck.md](runbooks/leader-election-stuck.md).

### Рекомендуемые алерты Prometheus

```yaml
# alerting_rules.yaml (пример для Prometheus Alertmanager)
groups:
  - name: eca-system
    rules:
      - alert: EcaHighCpu
        expr: process_cpu_usage{application="eca-system"} > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "ECA System высокая загрузка CPU"

      - alert: EcaReadinessDown
        expr: up{job="eca-system"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "ECA System недоступен (readiness DOWN)"

      - alert: EcaHikariPending
        expr: hikaricp_connections_pending{application="eca-system"} > 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Очередь за соединением HikariCP"

      - alert: EcaSlowRequests
        expr: histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{application="eca-system"}[5m])) > 1
        for: 3m
        labels:
          severity: warning
        annotations:
          summary: "p99 задержки HTTP > 1 с"
```

---

## Event Log (Tracking)

Event Log класса Tracking хранится в таблице `tracking_event_log` (с партиционированием по месяцам, миграция V37). Содержит все события обработки: получение ACARS-сообщений, переходы состояний экземпляров, решения движка ECA.

### Просмотр через UI

Раздел **Monitoring** в веб-интерфейсе (`http://eca.example.ru/monitoring`). Фильтрация по дате, борту, рейсу, типу события.

### Просмотр через REST API

```bash
# Последние 50 событий (все экземпляры)
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://eca.example.ru/api/executions?page=0&size=50' \
  | python3 -m json.tool

# События конкретного экземпляра
curl -s -H "Authorization: Bearer $TOKEN" \
  'http://eca.example.ru/api/executions/INSTANCE_ID/events' \
  | python3 -m json.tool
```

### Прямой запрос к БД

```sql
-- Последние 20 событий
SELECT created_at, event_type, instance_id, sequence_id, tail_number, message
FROM tracking_event_log
ORDER BY created_at DESC
LIMIT 20;

-- События по конкретному борту за сегодня
SELECT created_at, event_type, message
FROM tracking_event_log
WHERE tail_number = 'VP-BQR'
  AND created_at >= CURRENT_DATE
ORDER BY created_at DESC;

-- Размер партиций
SELECT tablename,
       pg_size_pretty(pg_total_relation_size('public.' || tablename)) AS size
FROM pg_tables
WHERE tablename LIKE 'tracking_event_log_%'
ORDER BY tablename DESC;
```

---

## Логи и трассировка

### Docker Compose

```bash
# Все логи приложения
docker logs eca-app

# Потоковый просмотр
docker logs -f eca-app

# Последние 100 строк
docker logs --tail=100 eca-app

# Фильтр по уровню (grep в shell)
docker logs eca-app 2>&1 | grep ERROR
```

### Kubernetes

```bash
# Логи конкретного pod'а
kubectl logs -n eca <POD_NAME> --tail=100

# Все pod'ы backend одновременно (с именем pod'а в начале строки)
kubectl logs -n eca -l app.kubernetes.io/component=backend --tail=100

# Потоковый просмотр всех реплик
kubectl logs -n eca -l app.kubernetes.io/component=backend -f

# Логи предыдущей (упавшей) копии pod'а
kubectl logs -n eca <POD_NAME> --previous
```

### Структура лог-записи (prod, JSON/ECS)

```json
{
  "@timestamp": "2026-06-28T12:34:56.789Z",
  "log.level": "INFO",
  "message": "Event processed",
  "service.name": "eca-system",
  "trace.id": "a1b2c3d4e5f6...",
  "span.id": "1234567890abcdef"
}
```

### Трассировка по correlationId

Каждый входящий ACARS-запрос получает `trace.id` (OpenTelemetry). Для поиска всех событий одного сообщения:

```bash
# В Docker Compose (поиск по correlationId в логах)
docker logs eca-app 2>&1 | grep '"trace.id":"TRACE_ID_VALUE"'

# В Kubernetes через grep
kubectl logs -n eca -l app.kubernetes.io/component=backend \
  | grep '"trace.id":"TRACE_ID_VALUE"'
```

При развёрнутом OpenTelemetry Collector и Jaeger/Grafana Tempo — трейс доступен по `trace.id` в UI.
