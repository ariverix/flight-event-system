-- Таблица Spring Modulith Transactional Outbox для надёжной доставки доменных событий.
-- Создаётся через Flyway чтобы Hibernate schema validation (ddl-auto: validate) не падала
-- на свежей БД при запуске через Docker.
--
-- См. диплом: раздел 1.4.1 (Transactional Outbox — паттерн надёжности)
CREATE TABLE IF NOT EXISTS event_publication (
    id                UUID                        NOT NULL,
    completion_date   TIMESTAMP(6) WITH TIME ZONE,
    event_type        VARCHAR(512)                NOT NULL,
    listener_id       VARCHAR(1024)               NOT NULL,
    publication_date  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    serialized_event  TEXT                        NOT NULL,
    PRIMARY KEY (id)
);
