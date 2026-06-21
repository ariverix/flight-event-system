-- P2-5: «position not reported in last {x} min» по паритету SITA Sequencer привязан к
-- Off-таймстампу (момент взлёта) как точке отсчёта — до Off у ВС физически нет ожидаемого потока
-- позиционных отчётов, поэтому проверка "нет позиции за окно" не должна заглядывать в эпоху ДО
-- взлёта. До этой миграции в системе НЕ было ни одного durable места, где хранился бы момент
-- смены стадии полёта (OOOI) по борту: notifyFlightStageChange (eventprocessor) публиковал
-- NormalizedEvent чисто in-flight, не записывая системное событие в messages (см. комментарий
-- в EventProcessorService — "смена стадии — системное событие, в таблицу messages не пишем"),
-- а ARINC 618 OOOI-метки (Arinc618Parser) кладутся в metadata конкретного сообщения, но это
-- сообщение ничем не помечено как "стадийное" на уровне схемы messages (нет колонки flight_stage).
--
-- flight_stage_events — отдельная узкая таблица фактов "стадия X наступила в момент T для борта Y"
-- (как OOOI-журнал, не сообщение и не Tracking Event Log — третья самостоятельная сущность):
--   - НЕ переиспользует messages: смена стадии не всегда сопровождается сохранённым входящим
--     сообщением (notifyFlightStageChange — отдельный канал уведомления, не ACARS-телеграмма);
--   - НЕ переиспользует tracking_event_log (V24): тот класса Tracking привязан к sequence_id/
--     instance_id конкретной последовательности и существует только если она запущена и включено
--     логирование — момент Off у борта объективен независимо от того, исполняется ли вообще
--     какая-то последовательность для этого борта.
CREATE TABLE flight_stage_events (
    id              BIGSERIAL       PRIMARY KEY,

    aircraft_id     VARCHAR(50)     NOT NULL,
    flight_number   VARCHAR(50),

    -- INIT/OUT/OFF/ON/IN/SUMMARY — ru.protectinfotrans.eca.FlightStage, как и везде в проекте
    -- хранится строкой имени enum (см. messages.position_source, V21 — тот же подход).
    stage           VARCHAR(20)     NOT NULL,

    occurred_at     TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

-- Горячий запрос — "последний момент стадии OFF (или любой другой) для конкретного борта"
-- (POSITION-критерий not-reported, см. CriterionEvaluator/ExecutionService). DESC по occurred_at
-- не нужен явно в индексе — Postgres сам использует индекс для MAX(occurred_at) с равенством по
-- (aircraft_id, stage) в WHERE.
CREATE INDEX idx_flight_stage_events_lookup
    ON flight_stage_events (aircraft_id, stage, occurred_at);
