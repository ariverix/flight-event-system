CREATE TABLE steps (
    id                      BIGSERIAL       PRIMARY KEY,
    sequence_id             BIGINT          NOT NULL REFERENCES sequences(id) ON DELETE CASCADE,
    order_index             INTEGER         NOT NULL,
    name                    VARCHAR(100),
    step_type               VARCHAR(50)     NOT NULL,
    config                  JSONB,
    timeout_seconds         INTEGER,
    on_success_action       VARCHAR(50)     NOT NULL,
    on_success_goto_step    INTEGER,
    on_success_notify       BOOLEAN         DEFAULT FALSE,
    on_failure_action       VARCHAR(50)     NOT NULL,
    on_failure_goto_step    INTEGER,
    on_failure_notify       BOOLEAN         DEFAULT FALSE,

    CONSTRAINT uq_steps_sequence_order UNIQUE (sequence_id, order_index)
);

CREATE INDEX idx_steps_sequence_id ON steps(sequence_id);
