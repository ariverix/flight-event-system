CREATE TABLE messages (
    id              BIGSERIAL       PRIMARY KEY,
    message_type    VARCHAR(50),
    template_name   VARCHAR(255),
    aircraft_id     VARCHAR(255),
    flight_number   VARCHAR(255),
    content         TEXT,
    metadata_json   JSONB,
    received_at     TIMESTAMP
);

-- Поиск сообщений по ВС
CREATE INDEX idx_messages_aircraft_received ON messages(aircraft_id, received_at);
