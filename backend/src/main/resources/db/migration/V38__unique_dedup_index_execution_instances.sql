-- V38 (db-dev): закрывает архитектурный backlog P1-7/P6-1 — гонка двойного старта инстанса
-- под несколькими репликами backend.
--
-- Пробел: ExecutionService.startExecution делал дедуп read-then-write —
--   existsByDedupKey(sequence, aircraft, flight, triggeringMessageId)  → (нет)  → save()
-- Проверка и вставка НЕ атомарны. При одной реплике и строго последовательной at-least-once
-- доставке ОДНОЙ публикации Spring Modulith (republish-on-restart/retry не диспетчеризует одну
-- публикацию в два потока — см. P1_7_OutboxRepublishAndIdempotencyScenarioIntTest) этого хватало.
-- Но k8s-деплой уже replicas:2 + HPA до 8 (P6-1): при повторной доставке ОДНОГО И ТОГО ЖЕ
-- сообщения (например ретрансляция ACARS-шлюзом) на РАЗНЫЕ реплики оба процесса могут
-- одновременно пройти existsByDedupKey (оба видят «инстанса ещё нет») до того, как любой из них
-- закоммитит INSERT — и создать ДВА дублирующих ExecutionInstance. Read-then-write без
-- ограничения БД такую гонку не ловит принципиально.
--
-- Решение (тот же принцип DB-level гарантии, что и single-fire WAIT-таймаутов P1-5,
-- claimExpiredTimeout): перенести гарантию уникальности старта в СТРОКУ БД — частичный
-- УНИКАЛЬНЫЙ индекс по дедуп-ключу. Второй конкурентный INSERT нарушает индекс и падает
-- (DataIntegrityViolationException), а не создаёт дубль; ExecutionService ловит это как
-- идемпотентный no-op (проигрыш гонки = «инстанс уже создан победителем»). Пред-проверка
-- existsByDedupKey остаётся как быстрый путь для доминирующего последовательного случая
-- (redelivery после коммита победителя) — без исключения; уникальный индекс нужен только для
-- истинной параллельной гонки.
--
-- Ключ и семантика NULL — ТОЧНО как у существующей дедуп-проверки (V23, ADR-0002):
--   * Частичный WHERE triggering_message_id IS NOT NULL — дедуп применяется ТОЛЬКО к
--     message-triggered стартам. Старты без исходного сообщения (triggering_message_id = NULL,
--     например notifyFlightStageChange, ручной старт) НЕ дедуплицируются по сообщению —
--     несколько таких инстансов легитимны (см. javadoc ExecutionService.startExecution).
--     Индекс их не покрывает и не ограничивает.
--   * NULLS NOT DISTINCT (Postgres 15+, у нас 16): в пределах индексируемых строк
--     triggering_message_id всегда NOT NULL, но flight_number МОЖЕТ быть NULL. Без NULLS NOT
--     DISTINCT две строки с одинаковыми (sequence, aircraft, NULL flight, message) НЕ считались
--     бы дубликатами (в SQL NULL <> NULL) — и уникальный индекс пропустил бы дубль, тогда как
--     Spring Data existsBy...FlightNumber(null) транслируется в flight_number IS NULL и ДЕДУПИТ
--     такой случай. NULLS NOT DISTINCT приводит индекс в соответствие с существующей семантикой
--     пред-проверки (NULL flight_number трактуется как «равный»), чтобы БД-гарантия и
--     application-проверка были согласованы.
--
-- Существующие строки НЕ нарушают индекс при создании: дедуп-проверка (V23/P1-7) действует с
-- момента её ввода, поэтому дублей по (sequence, aircraft, flight, non-null message) в данных
-- быть не должно. Индекс лишь превращает уже соблюдаемый инвариант в гарантию уровня БД.
--
-- Старый НЕуникальный idx_exec_dedup_trigger (V23) удаляется: дедуп-запрос всегда идёт с
-- triggering_message_id IS NOT NULL, поэтому частичный уникальный индекс полностью покрывает
-- тот же паттерн поиска (равенство по всем четырём колонкам); другие запросы движка этот индекс
-- не использовали (см. комментарий V23). Держать оба — избыточно.

DROP INDEX IF EXISTS idx_exec_dedup_trigger;

CREATE UNIQUE INDEX idx_exec_dedup_trigger_unique
    ON execution_instances (sequence_id, aircraft_id, flight_number, triggering_message_id)
    NULLS NOT DISTINCT
    WHERE triggering_message_id IS NOT NULL;
