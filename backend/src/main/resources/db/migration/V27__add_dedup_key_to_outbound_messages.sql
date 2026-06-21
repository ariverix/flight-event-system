-- P2-3 фикс: дедуп-ключ идемпотентности outbound-сообщений на рестарт-replay
-- (ExecutionService#resumeRunningInstanceAfterRestart) — без ключа повторный прогон
-- ACTION-шага после рестарта процесса заново ставил в очередь то же uplink/ground
-- сообщение (см. OutboundMessage#executionInstanceId javadoc).
--
-- Аддитивная миграция: только новые NULL-колонки + partial unique индекс, без бэкафилла —
-- существующие строки outbound_messages (V26) получают NULL в обеих колонках и тем
-- самым исключаются из UNIQUE-ограничения (тот же partial-index паттерн, что у
-- V25 idx_messages_external_message_id_unique). Безопасно на пустой и заполненной БД,
-- без долгих локов — ALTER ADD COLUMN NULL и CREATE INDEX (без CONCURRENTLY: миграции
-- Flyway уже выполняются в транзакции/при старте приложения, таблица некрупная).
ALTER TABLE outbound_messages
    ADD COLUMN execution_instance_id BIGINT NULL,
    ADD COLUMN step_order_index INTEGER NULL;

-- UNIQUE только среди строк с обеими частями ключа (ACTION-шаг enqueue всегда имеет обе;
-- строки от вызовов без execution-контекста, напр. IntegrationService, обе NULL → исключены).
-- Тот же partial-index паттерн, что V25 idx_messages_external_message_id_unique.
CREATE UNIQUE INDEX idx_outbound_messages_instance_step_unique
    ON outbound_messages (execution_instance_id, step_order_index)
    WHERE execution_instance_id IS NOT NULL AND step_order_index IS NOT NULL;
