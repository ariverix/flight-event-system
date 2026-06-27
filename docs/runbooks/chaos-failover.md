# Runbook: Chaos и Failover тесты — ECA System

> P5-4 | Статус: Done | Ответственный: test-engineer + db-dev

## Scope-граница

Текущая ECA — **один узел PostgreSQL** (HA-реплики, leader election → P6-1, не реализовано).
**Настоящий replica-failover** (потоковая репликация, автоматическое переключение лидера) — вне
объёма P5-4; реализуется в P6-1. Этот раздел описывает:
- Устойчивость к **потере исходящего канала** (circuit breaker CLOSED→OPEN→восстановление).
- Устойчивость к **недоступности БД** (предсказуемая деградация без зависания).

---

## Сценарий 1: Падение исходящего канала (UPLINK/GROUND)

### Описание
Исходящий канал ACARS (UPLINK или GROUND) временно недоступен. ECA должна:
1. Зафиксировать серию сбоев и перейти в состояние OPEN (fail-fast, без бесполезных повторных попыток).
2. Сигнализировать о деградации через `/actuator/health/readiness` (HTTP 503).
3. После восстановления канала: провести пробную HALF_OPEN попытку → при успехе перейти в CLOSED → readiness UP.

### Автоматизированный тест

`backend/src/test/java/.../reliability/P5_4_ChannelChaosIntTest.java`

**Что проверяет:**
- При 5 последовательных сбоях доставки (params.__simulateFailure=true) circuit breaker переходит CLOSED→OPEN.
- `/actuator/health/readiness` возвращает HTTP 503 при OPEN breaker (через IntegrationChannelsHealthIndicator).
- После перемотки `opened_at` на 5 минут назад (таймаут 30с истёк): следующая успешная доставка вызывает HALF_OPEN→CLOSED.
- `/actuator/health/readiness` возвращает HTTP 200 (UP) после закрытия breaker.

### Воспроизведение вручную

```sh
# 1. Запустить приложение с активным UPLINK-каналом.
# 2. Симулировать сбои: отправить 5 UPLINK-сообщений с флагом сбоя.
# (В проде: прервать сетевое соединение к ACARS/AFTN-серверу.)

# 3. Проверить circuit breaker напрямую:
psql -h $DB_HOST -U $DB_USER -d $DB_NAME \
  -c "SELECT channel, state, consecutive_failures, opened_at FROM channel_circuit_breakers;"
# → state = 'OPEN'

# 4. Проверить readiness:
curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"DOWN"} HTTP 503

# 5. Симулировать восстановление (переместить opened_at в прошлое):
psql -h $DB_HOST -U $DB_USER -d $DB_NAME \
  -c "UPDATE channel_circuit_breakers SET opened_at = NOW() - INTERVAL '5 minutes' WHERE channel = 'UPLINK';"

# 6. Дождаться следующего тика OutboundMessageDeliveryScheduler (каждые 5с).
#    Первая успешная доставка → HALF_OPEN→CLOSED.

# 7. Проверить восстановление:
curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"UP"} HTTP 200
psql -h $DB_HOST -U $DB_USER -d $DB_NAME \
  -c "SELECT channel, state, consecutive_failures FROM channel_circuit_breakers;"
# → state = 'CLOSED', consecutive_failures = 0
```

### Ожидаемое поведение
| Состояние | circuit breaker | readiness | Поведение планировщика |
|-----------|----------------|-----------|----------------------|
| Нормальное | CLOSED | UP | Доставляет сообщения |
| Сбои (< 5) | CLOSED, failures++ | UP | Backoff, повторные попытки |
| 5 сбоев | OPEN | DOWN (503) | Fail-fast, сообщения в PENDING |
| Таймаут (30с) | OPEN→HALF_OPEN | DOWN (503) | Одна пробная попытка |
| Успешная проба | CLOSED | UP | Нормальная доставка |
| Неуспешная проба | OPEN (новый openedAt) | DOWN (503) | Fail-fast, новый таймаут |

### Опорный механизм
Circuit breaker реализован в P2-6: `CircuitBreakerPolicy` (логика), `ChannelCircuitBreakerJpaAdapter`
(персистентность). Состояния сохранены в `channel_circuit_breakers`. Переживает рестарт сервиса.

---

## Сценарий 2: Недоступность БД PostgreSQL

### Описание
БД PostgreSQL становится недоступной (узел упал, сетевой разрыв, maintenance). ECA должна:
1. Обнаружить потерю БД в разумное время (HikariCP connectionTimeout ≤ 30с).
2. Не зависать навечно — запросы должны отклоняться, а не блокировать потоки.
3. `/actuator/health/readiness` сообщает о потере БД (status: DOWN).
4. После возврата БД — HikariCP восстанавливает пул, приложение работает нормально.

### Автоматизированный тест

`backend/src/test/java/.../reliability/P5_4_DatabaseChaosIntTest.java`

**Что проверяет (3 теста):**
1. После остановки контейнера с PostgreSQL: попытка получить соединение завершается ошибкой за ≤ 5 с (HikariCP connectionTimeout=3000мс), не зависает.
2. `DataSourceHealthIndicator` с недоступным источником данных возвращает Status.DOWN за ≤ 5 с.
3. Полный lifecycle: UP (контейнер запущен) → DOWN (контейнер остановлен) → UP (новый контейнер, «БД вернулась»).

### Воспроизведение вручную

```sh
# 1. Симулировать потерю БД (остановить postgres-контейнер):
docker stop eca-pg

# 2. Проверить readiness немедленно:
curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"DOWN"} HTTP 503 (БД не отвечает)

# 3. Убедиться, что приложение не зависает:
# Запросы к API должны отклоняться с 503/500 в течение секунд, не зависать на минуты.

# 4. Восстановить БД:
docker start eca-pg

# 5. Подождать 15-30 секунд (HikariCP: connectionTimeout + первый keepAlive):
sleep 30

# 6. Проверить восстановление:
curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"UP"} HTTP 200
```

### Конфигурация HikariCP для предсказуемой деградации

Убедитесь, что в `application.yml` (или env) заданы разумные таймауты пула:

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: 10000     # 10с — не ждать соединение вечно
      validation-timeout: 5000      # 5с — валидация keepAlive/isValid
      connection-test-query: SELECT 1
      keepalive-time: 30000         # 30с — keepAlive для обнаружения мёртвых соединений
      max-lifetime: 1800000         # 30 мин — максимальный возраст соединения
```

Текущие настройки в `application.yml` используют Spring Boot defaults (Hikari):
- `connectionTimeout`: 30000 мс (30с)
- `validationTimeout`: 5000 мс (5с)

Рекомендация для прода: уменьшить `connectionTimeout` до 10с для более быстрого failover.

### Scope-граница (P6-1)

Автоматическое переключение на реплику при падении primary — не входит в P5-4.
При появлении HA (P6-1, Patroni/pgPool):
- Время переключения primary→replica: 10-30с (зависит от TTL DNS/VIP).
- HikariCP пересоздаст соединения после TCP-таймаута.
- ECA не требует кода изменений — вся логика failover в инфраструктуре.

---

## Сценарий 3: Гонка на общем событии (DLQ overflow)

### Описание
При высоком throughput входящих сообщений и массовых сбоях обработки DLQ (dead_letter_messages)
может накопить >100 записей в статусе NEW → readiness переходит в OUT_OF_SERVICE (HTTP 503).

### Воспроизведение вручную

```sh
# Добавить >100 NEW-записей в DLQ (тест P5-3 уже делает это):
psql -h $DB_HOST -U $DB_USER -d $DB_NAME -c "
  INSERT INTO dead_letter_messages (source, raw_payload, reason, status, created_at, attempts)
  SELECT 'RAW_GATEWAY', 'payload', 'test-reason', 'NEW', NOW(), 0
  FROM generate_series(1, 101);
"

# Проверить:
curl -s http://localhost:8080/actuator/health/readiness
# → {"status":"OUT_OF_SERVICE"} HTTP 503

# Устранение: оператор обрабатывает DLQ через /api/v1/dlq (RBAC OPERATOR/ADMIN):
# - REPROCESS: повторно обработать записи
# - DISCARD: отбросить записи (подтверждение ручной обработки)
curl -X POST http://localhost:8080/api/v1/dlq/{id}/discard \
  -H "Authorization: Bearer $OPERATOR_TOKEN"
```

### Ожидаемое поведение

- DLQ > 100 → OUT_OF_SERVICE (деградировано, но сервис работает).
- DLQ ≤ 100 → UP.
- Порог настраивается: `app.health.dlq-critical-size` (default 100).

---

## Связанные тесты и код

| Тест | Что тестирует |
|------|--------------|
| `P5_4_BackupRestoreIntTest` | pg_dump→pg_restore, целостность данных |
| `P5_4_ChannelChaosIntTest` | circuit breaker CLOSED→OPEN→recovery, readiness 503→UP |
| `P5_4_DatabaseChaosIntTest` | DB unavailability, fail-fast (не зависание), health DOWN |
| `P5_3_HealthProbesIntTest` | Readiness 503 при OPEN breaker / DLQ overflow |
| `P2_6_DlqAndResilienceScenarioIntTest` | Детали circuit breaker / backoff / DLQ |

---

## Связь с другими фазами

- **P5-3** (Done): health liveness/readiness/startup пробы; IntegrationChannelsHealthIndicator.
- **P2-6** (Done): DLQ + circuit breaker на исходящих каналах; backoff.
- **P6-1** (Pending): HA-реплики, leader election — настоящий replica-failover.
- **P8-1** (Pending): Kubernetes/Helm, readiness/liveness в pod spec.
