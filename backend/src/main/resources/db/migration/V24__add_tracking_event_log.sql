-- P1-8 (часть 1 — схема). Event Log класса Tracking (SITA): персистентный журнал
-- старт/стоп последовательности и завершения шагов, отдельный от:
--   - audit_log (V7/V20)         — действия ПОЛЬЗОВАТЕЛЯ (CRUD, login и т.д.);
--   - step_executions (V5)       — техническая история переходов ОДНОГО execution_instance,
--                                  каскадно удаляется вместе с инстансом (ON DELETE CASCADE),
--                                  не привязана к борту/рейсу на уровне записи и не различает
--                                  событие "старт/стоп последовательности" как таковое;
--   - event_publication (V10)   — Modulith transactional outbox, технический канал доставки
--                                  событий между модулями, не предназначен для просмотра оператором.
--
-- Tracking Event Log — бизнес-журнал для оператора (просмотр истории работы последовательности
-- по борту/рейсу), должен переживать удаление execution_instance/sequence, поэтому НЕ использует
-- FK ON DELETE CASCADE на эти таблицы (см. также execution_instances.sequence_id, V4: тот же
-- принцип "без FK" уже применён там по причине границ модуля execution/sequence).
--
-- Логика ЗАПИСИ событий в этот журнал — часть 2 (observability-agent). Эта миграция —
-- только схема: флаг включения логирования на последовательности + таблица журнала + индексы.

-- 1. Флаг "логирование Tracking Event Log включено" на уровне последовательности (per-sequence,
--    как в SITA). DEFAULT TRUE: промышленная система должна по умолчанию вести трекинг-журнал
--    для аудита/диагностики поведения последовательностей (расследование инцидентов с реальными
--    рейсами требует истории по умолчанию, а не после того как её включили вручную); оператор,
--    которому шум не нужен, может выключить логирование на конкретной последовательности явно.
--    NOT NULL + DEFAULT TRUE бэкафиллит все существующие строки (включая демо-сценарии V9/V14).
ALTER TABLE sequences
    ADD COLUMN logging_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. Таблица журнала.
CREATE TABLE tracking_event_log (
    id              BIGSERIAL       PRIMARY KEY,

    -- Без FK (см. комментарий выше): запись должна переживать удаление sequence/instance.
    sequence_id     BIGINT          NOT NULL,
    instance_id     BIGINT,                     -- NULL для SEQUENCE_STARTED до создания инстанса

    aircraft_id     VARCHAR(50)     NOT NULL,
    flight_number   VARCHAR(50),

    -- SEQUENCE_STARTED, SEQUENCE_STOPPED, SEQUENCE_ABORTED, STEP_COMPLETED.
    -- STOPPED соответствует штатному завершению (ExecutionStatus.COMPLETED),
    -- ABORTED — терминации через decision ABORT (ExecutionStatus.ABORTED), см. P1-1/P1-2.
    event_type      VARCHAR(50)     NOT NULL,

    step_index      INTEGER,                    -- только для STEP_COMPLETED
    step_result     VARCHAR(20),                 -- SUCCESS/FAILURE, только для STEP_COMPLETED

    details_json    JSONB,

    -- Сквозной идентификатор запроса/сообщения — тот же формат, что audit_log.correlation_id (V20).
    correlation_id  VARCHAR(64),

    created_at      TIMESTAMP       NOT NULL DEFAULT now()
);

-- 3. Индексы под чтение оператором (журнал/просмотр по сущности), без лишних:
--    - по sequence_id: "история по последовательности" (основной экран Event Log в SITA — per-sequence).
CREATE INDEX idx_tracking_event_log_sequence ON tracking_event_log (sequence_id);

--    - по (aircraft_id, flight_number): "история по борту/рейсу" — второй основной фильтр оператора,
--      сопоставимо с execution_instances (V4) и его существующими индексами по aircraft_id.
CREATE INDEX idx_tracking_event_log_aircraft ON tracking_event_log (aircraft_id, flight_number);

--    - по instance_id: drill-down "все события конкретного запуска" (включая шаги). NULL допустим
--      (для SEQUENCE_STARTED), индекс по-прежнему полезен для всех записей где instance_id задан.
CREATE INDEX idx_tracking_event_log_instance ON tracking_event_log (instance_id);

--    - по created_at: журнал просматривается как хронологическая лента (последние события),
--      нужен для ORDER BY created_at DESC LIMIT/постраничной выборки без полного скана.
CREATE INDEX idx_tracking_event_log_created_at ON tracking_event_log (created_at);
