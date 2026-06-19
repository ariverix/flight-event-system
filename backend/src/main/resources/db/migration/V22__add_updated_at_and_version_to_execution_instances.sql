-- P1-3: Персистентный стейт инстанса последовательности.
--
-- Терминологическое соответствие: задача P1-3 говорит о таблице "sequence_instance",
-- но в проекте стейт инстанса (последовательность × борт, свой указатель шага, свой
-- контекст) уже реализован как execution_instances (V4) — это и есть канонический
-- sequence instance в терминах SITA. Новую таблицу-дубль не создаём.
--
-- Аудит (P1-3, часть 1) показал, что execution_instances УЖЕ покрывает:
--   - текущий шаг       -> current_step_index
--   - статус            -> status
--   - таймауты          -> wait_started_at, wait_timeout_at
--   - контекст          -> context JSONB (слот заложен в V4; наполнение данными —
--                          задача sequence-engine-dev, часть 2, "save on every transition")
--   - привязка к борту/рейсу/последовательности -> aircraft_id, flight_number, sequence_id
--     (обеспечивает семантику "одна последовательность на много бортов = разные
--     инстансы со своим независимым current_step_index и context")
--
-- Не хватало:
--   - updated_at  — единая метка последнего изменения стейта инстанса (по аналогии
--     с sequences.updated_at). started_at/completed_at/wait_started_at фиксируют
--     конкретные события жизненного цикла, но не "последнее изменение вообще" —
--     нужна для мониторинга/диагностики (stale instances) и для части 2
--     (save-on-every-transition должен обновлять этот штамп при каждом save()).
--   - version     — НЕ реализуем optimistic locking сейчас (это P1-6). Колонку
--     закладываем nullable, без @Version в JPA, чтобы P1-6 могла включить
--     блокировку без новой ALTER-миграции и без блокировки текущих INSERT/UPDATE
--     (Hibernate с обычным @Column поля не трогает при отсутствии @Version).

ALTER TABLE execution_instances
    ADD COLUMN updated_at TIMESTAMP,
    ADD COLUMN version    BIGINT;

-- Бэкафилл существующих строк (демо/прод): updated_at = COALESCE(completed_at, started_at, now())
-- чтобы не оставлять NULL в исторических записях с уже завершённым жизненным циклом.
UPDATE execution_instances
SET updated_at = COALESCE(completed_at, started_at, now())
WHERE updated_at IS NULL;

-- version = 0 по умолчанию для существующих строк — нейтральная стартовая отметка,
-- если P1-6 включит @Version поверх этой колонки.
UPDATE execution_instances
SET version = 0
WHERE version IS NULL;

ALTER TABLE execution_instances
    ALTER COLUMN version SET DEFAULT 0;

-- Поиск "зависших"/давно не обновлявшихся активных инстансов (диагностика, часть 2/мониторинг)
CREATE INDEX idx_exec_status_updated_at ON execution_instances(status, updated_at);
