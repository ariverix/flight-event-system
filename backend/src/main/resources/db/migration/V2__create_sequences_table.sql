CREATE TABLE sequences (
    id                  BIGSERIAL       PRIMARY KEY,
    name                VARCHAR(100)    NOT NULL,
    description         VARCHAR(500),
    status              VARCHAR(50)     NOT NULL,
    start_criteria      JSONB,
    stop_criteria       JSONB,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    created_by          BIGINT
);
