-- P2-3: durable исходящий шлюз (integration) — персистентная очередь uplink/ground
-- сообщений, ставящихся ACTION-шагами движка (SEND_UPLINK computer-generated|external-user,
-- SEND_GROUND с получателями), с асинхронной доставкой отдельным поллером
-- (OutboundMessageDeliveryScheduler) и атомарным single-fire claim'ом (условный
-- UPDATE PENDING -> SENDING) по аналогии с claim'ом durable WAIT-таймаутов (P1-5,
-- ExecutionJpaRepository#claimExpiredTimeout).
--
-- Без FK на execution_instances/sequences — тот же принцип "без FK" уже применён в
-- execution_instances.sequence_id (V4) и tracking_event_log (V24): запись о постановке
-- сообщения в очередь должна переживать удаление инстанса/последовательности, модуль
-- integration не зависит от схемы execution/sequence (границы Modulith).
--
-- Аддитивная миграция: новая таблица, не затрагивает существующие данные/демо-сценарии
-- V9/V11/V14 — безопасна на пустой и заполненной БД.
CREATE TABLE outbound_messages (
    id              BIGSERIAL       PRIMARY KEY,
    message_type    VARCHAR(20)     NOT NULL,   -- UPLINK | GROUND
    aircraft_id     VARCHAR(50),                -- только UPLINK
    flight_number   VARCHAR(50),                -- зарезервировано
    recipients      JSONB,                      -- только GROUND
    template_name   VARCHAR(255)    NOT NULL,
    params          JSONB,
    uplink_origin   VARCHAR(30),                -- COMPUTER_GENERATED | EXTERNAL_USER
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',  -- PENDING|SENDING|SENT|FAILED
    attempts        INTEGER         NOT NULL DEFAULT 0,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    sent_at         TIMESTAMP,
    last_error      TEXT
);

-- Горячий путь поллера: "выбрать самые старые PENDING-кандидаты на доставку"
-- (OutboundMessageJpaRepository#findPendingCandidates, ORDER BY created_at ASC) —
-- индекс по (status, created_at) покрывает и фильтр, и сортировку без сортировки в памяти.
CREATE INDEX idx_outbound_messages_status_created_at ON outbound_messages (status, created_at);

-- Второй ожидаемый запрос движка/оператора: история исходящих сообщений по конкретному борту.
CREATE INDEX idx_outbound_messages_aircraft ON outbound_messages (aircraft_id);
