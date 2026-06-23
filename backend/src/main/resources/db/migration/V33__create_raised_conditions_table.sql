-- P3-3: движок условий/алертов (raise/close custom condition).
-- Per-flight (aircraft_id + flight_number), независимый уровень алерта, мягкое закрытие (closed_at).
CREATE TABLE raised_conditions (
    id             BIGSERIAL PRIMARY KEY,
    aircraft_id    VARCHAR(50)  NOT NULL,
    flight_number  VARCHAR(50)  NOT NULL,
    condition_name VARCHAR(255) NOT NULL,
    alert_level    VARCHAR(20)  NOT NULL,
    raised_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    closed_at      TIMESTAMP
);

-- "Нельзя поднять дважды одним именем" — уникальность АКТИВНОЙ (не закрытой) строки на рейс.
-- Defense-in-depth к явной проверке в ConditionService#raiseCondition против конкурентной гонки.
CREATE UNIQUE INDEX uq_raised_conditions_active
    ON raised_conditions (aircraft_id, flight_number, condition_name)
    WHERE closed_at IS NULL;

-- Лукапы активных условий рейса (getActiveConditions / авто-закрытие на IN/SUMMARY).
CREATE INDEX idx_raised_conditions_active_flight
    ON raised_conditions (aircraft_id, flight_number)
    WHERE closed_at IS NULL;
