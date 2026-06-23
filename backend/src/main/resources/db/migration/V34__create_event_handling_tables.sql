-- P3-4: Event Handling (папки + обработчики событий) + Notify-каналы (email/webhook) с
-- идемпотентной доставкой уведомлений на success/false шага.

-- Папки последовательностей (паритет SITA: организация sequences по папкам, иерархия).
CREATE TABLE folders (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    parent_id  BIGINT REFERENCES folders(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_folders_parent ON folders (parent_id);

-- Принадлежность последовательности папке (nullable — последовательность может быть вне папок).
-- FK на folders(id) — на уровне БД (единый Flyway); Java-зависимости sequence→eventhandling нет
-- (поле — простой Long, не JPA-связь на Folder из чужого модуля).
ALTER TABLE sequences ADD COLUMN folder_id BIGINT REFERENCES folders(id);
CREATE INDEX idx_sequences_folder ON sequences (folder_id);

-- Обработчики событий: конфигурация «куда/как уведомлять» на уровне ПАПКИ (наследуется
-- вложенными папками/последовательностями) ИЛИ на уровне ПОСЛЕДОВАТЕЛЬНОСТИ (переопределяет
-- наследование). scope_id ссылается на folders.id (scope=FOLDER) или sequences.id (scope=SEQUENCE).
CREATE TABLE event_handlers (
    id           BIGSERIAL PRIMARY KEY,
    scope        VARCHAR(20)  NOT NULL,   -- FOLDER | SEQUENCE
    scope_id     BIGINT       NOT NULL,
    trigger_type VARCHAR(20)  NOT NULL,   -- ON_SUCCESS | ON_FAILURE | ON_ANY
    channel      VARCHAR(20)  NOT NULL,   -- EMAIL | WEBHOOK
    target       VARCHAR(500) NOT NULL,   -- email-адрес или webhook URL
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_event_handlers_scope ON event_handlers (scope, scope_id);

-- Журнал доставленных уловедомлений — durable дедуп-реестр (паритет идемпотентности P1-7/ADR-0002:
-- естественный дедуп-ключ (execution_id, step_index, result, handler_id) уже несёт само событие).
-- Повторная доставка StepNotificationEvent (republish-on-restart/retry) → конфликт по UNIQUE → no-op.
CREATE TABLE notification_deliveries (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT       NOT NULL,
    step_index   INT          NOT NULL,
    result       VARCHAR(20)  NOT NULL,   -- SUCCESS | FAILURE
    handler_id   BIGINT       NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    target       VARCHAR(500) NOT NULL,
    status       VARCHAR(20)  NOT NULL,   -- SENT | FAILED
    delivered_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_notification_deliveries_dedup
    ON notification_deliveries (execution_id, step_index, result, handler_id);
