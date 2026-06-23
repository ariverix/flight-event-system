-- P4-2: refresh-токены с ротацией и инвалидизацией.
-- Хранится ТОЛЬКО SHA-256-хэш токена (не сам токен) — даже при утечке БД токены неюзабельны.
-- SHA-256 (а не BCrypt) — токен высокоэнтропийный (256 бит SecureRandom), брутфорс по хэшу
-- нереален, BCrypt оправдан только для низкоэнтропийных паролей (см. ADR-0003).
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
