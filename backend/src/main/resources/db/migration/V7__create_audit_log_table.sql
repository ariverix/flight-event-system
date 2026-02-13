CREATE TABLE audit_log (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT,
    action          VARCHAR(255),
    entity_type     VARCHAR(255),
    entity_id       BIGINT,
    details_json    JSONB,
    created_at      TIMESTAMP
);
