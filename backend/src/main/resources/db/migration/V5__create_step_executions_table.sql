CREATE TABLE step_executions (
    id                      BIGSERIAL       PRIMARY KEY,
    execution_instance_id   BIGINT          NOT NULL REFERENCES execution_instances(id) ON DELETE CASCADE,
    step_index              INTEGER,
    step_type               VARCHAR(50),
    result                  VARCHAR(50),
    transition_action       VARCHAR(50),
    transition_target       INTEGER,
    details_json            JSONB,
    executed_at             TIMESTAMP
);

CREATE INDEX idx_step_exec_instance ON step_executions(execution_instance_id);
