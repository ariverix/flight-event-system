-- P2-6: DLQ для сбойных входящих сообщений + durable circuit breaker по каналам доставки +
-- backoff на outbound_messages (V26/V27) — см. DeadLetterMessage/ChannelCircuitBreaker/
-- OutboundMessage (integration.domain) для entity-маппинга, которому соответствует этот DDL.
--
-- Аддитивная миграция: 2 новые таблицы + 1 новая колонка на существующей таблице, без
-- изменения/удаления существующих данных/демо-сценариев (V9/V11/V14) — безопасна на пустой
-- и заполненной БД. outbound_messages в демо-сценариях пуста (P2-3 поллер реальных строк не
-- создаёт без живого ECA ACTION-шага SEND_UPLINK/SEND_GROUND), backfill UPDATE безвреден и
-- быстр даже на заполненной таблице. Без FK — тот же принцип "без FK", что у outbound_messages
-- (V26) и tracking_event_log (V24): DLQ-запись/circuit breaker самодостаточны, не зависят от
-- схемы execution/sequence (границы Modulith).

-- 1. DLQ для сбойных входящих сообщений (DeadLetterMessage) — раз сообщение не удалось
-- разобрать/обработать на приёме, оно персистится здесь вместе с сырым телом, форматом и
-- причиной сбоя для дальнейшего ручного reprocess оператором.
CREATE TABLE dead_letter_messages (
    id                     BIGSERIAL PRIMARY KEY,
    source                 VARCHAR(30) NOT NULL,   -- RAW_GATEWAY | STRUCTURED_GATEWAY
    format                 VARCHAR(20),
    raw_payload            TEXT NOT NULL,
    request_context        TEXT,
    reason                 TEXT NOT NULL,
    stack_trace            TEXT,
    status                 VARCHAR(20) NOT NULL,   -- NEW | REPROCESSED | DISCARDED (default из onCreate, не из схемы)
    attempts               INTEGER NOT NULL DEFAULT 0,
    reprocessed_message_id BIGINT,
    correlation_id         VARCHAR(64),
    created_at             TIMESTAMP NOT NULL DEFAULT now(),
    last_attempt_at        TIMESTAMP
);

-- Горячий путь оператора: список DLQ-записей по статусу (DeadLetterJpaRepository#findByStatus),
-- упорядоченный по времени поступления — индекс по (status, created_at) покрывает фильтр и
-- сортировку без сортировки в памяти (тот же паттерн, что idx_outbound_messages_status_created_at, V26).
CREATE INDEX idx_dead_letter_messages_status_created_at ON dead_letter_messages (status, created_at);

-- Сквозная корреляция по борту/рейсу (Event Log) — тот же принцип, что correlation_id в
-- audit_log (V20) и outbound_messages (V26).
CREATE INDEX idx_dead_letter_messages_correlation_id ON dead_letter_messages (correlation_id);

-- 2. Durable circuit breaker по каналам доставки (ChannelCircuitBreaker) — одна строка на
-- OutboundMessageType (UPLINK/GROUND), PK = channel (естественный ключ, без auto-generate —
-- @Id @Enumerated(EnumType.STRING) на channel в сущности).
CREATE TABLE channel_circuit_breakers (
    channel               VARCHAR(20) PRIMARY KEY,   -- UPLINK | GROUND (OutboundMessageType)
    state                 VARCHAR(20) NOT NULL DEFAULT 'CLOSED',  -- CLOSED | OPEN | HALF_OPEN
    consecutive_failures  INTEGER NOT NULL DEFAULT 0,
    opened_at             TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT now()
);

-- 3. Backoff на outbound_messages (V26/V27) — не раньше какого момента поллер может забрать
-- запись на следующую попытку (экспоненциальный backoff после сбоя, OutboundBackoffPolicy),
-- вместо немедленного возврата в PENDING (как было в P2-3 без backoff).
ALTER TABLE outbound_messages ADD COLUMN next_attempt_at TIMESTAMP;

-- Backfill существующих строк (демо: таблица пуста, UPDATE безвреден и мгновенен; на
-- заполненной БД — некрупная таблица очередей доставки, без долгих локов): доступны немедленно,
-- как при первой постановке (см. OutboundMessage#onCreate, nextAttemptAt = createdAt по умолчанию).
UPDATE outbound_messages SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;

-- Горячий путь поллера после P2-6: "выбрать самые старые PENDING-кандидаты, чей backoff истёк"
-- (OutboundMessageJpaRepository#findPendingCandidates: status = 'PENDING' AND next_attempt_at <= :now,
-- ORDER BY created_at ASC) — новый индекс по (status, next_attempt_at) покрывает фильтр backoff.
-- Старый индекс idx_outbound_messages_status_created_at (V26) НЕ удаляется: остаётся валидным
-- для прежнего паттерна доступа (status, created_at) и не создаёт конфликта с новым индексом.
CREATE INDEX idx_outbound_messages_status_next_attempt_at ON outbound_messages (status, next_attempt_at);
