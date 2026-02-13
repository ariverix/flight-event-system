CREATE TABLE execution_instances (
    id                  BIGSERIAL       PRIMARY KEY,
    sequence_id         BIGINT          NOT NULL,
    aircraft_id         VARCHAR(255)    NOT NULL,
    flight_number       VARCHAR(255),
    status              VARCHAR(50)     NOT NULL,
    current_step_index  INTEGER,
    context             JSONB,
    wait_started_at     TIMESTAMP,
    wait_timeout_at     TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP
);

-- Поиск активных экземпляров по ВС
CREATE INDEX idx_exec_aircraft_status ON execution_instances(aircraft_id, status);

-- Все экземпляры последовательности
CREATE INDEX idx_exec_sequence_id ON execution_instances(sequence_id);

-- Поиск истёкших таймаутов
CREATE INDEX idx_exec_status_timeout ON execution_instances(status, wait_timeout_at);
