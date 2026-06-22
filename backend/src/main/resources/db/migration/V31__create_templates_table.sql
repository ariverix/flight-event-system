CREATE TABLE templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500),
    message_type VARCHAR(20) NOT NULL,   -- DOWNLINK | UPLINK | GROUND
    origin VARCHAR(20),                   -- COMPUTER_GENERATED | EXTERNAL_USER; NULL для DOWNLINK
    category VARCHAR(100) NOT NULL DEFAULT 'GENERAL',
    body TEXT NOT NULL,                   -- тело с плейсхолдерами {{var}}
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_templates_name ON templates (name);
CREATE INDEX idx_templates_message_type ON templates (message_type);
CREATE INDEX idx_templates_category ON templates (category);
CREATE INDEX idx_templates_active ON templates (active);
