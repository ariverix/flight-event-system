CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    full_name       VARCHAR(255),
    role            VARCHAR(50),
    enabled         BOOLEAN         DEFAULT TRUE,
    created_at      TIMESTAMP
);
