CREATE TABLE custom_field_rules (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255) NOT NULL UNIQUE,
    description     VARCHAR(500),
    message_type    VARCHAR(20)  NOT NULL,
    template_name   VARCHAR(255),
    extraction_source VARCHAR(20) NOT NULL,
    pattern         TEXT NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_custom_field_rules_message_type_active ON custom_field_rules (message_type, active);

CREATE TABLE custom_field_values (
    id                BIGSERIAL PRIMARY KEY,
    field_name        VARCHAR(255) NOT NULL,
    aircraft_id       VARCHAR(50)  NOT NULL,
    flight_number     VARCHAR(50)  NOT NULL,
    value             TEXT,
    source_message_id BIGINT,
    extracted_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at         TIMESTAMP
);
CREATE UNIQUE INDEX uq_custom_field_values_flight_field ON custom_field_values (aircraft_id, flight_number, field_name);
CREATE INDEX idx_custom_field_values_active_lookup ON custom_field_values (aircraft_id, flight_number, closed_at);
