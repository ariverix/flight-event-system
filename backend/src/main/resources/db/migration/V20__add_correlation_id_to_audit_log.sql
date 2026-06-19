-- Добавляет колонку correlation_id в audit_log для связывания записей аудита
-- со структурными JSON-логами по сквозному идентификатору запроса/сообщения
-- (см. ru.protectinfotrans.eca.CorrelationContext.CORRELATION_ID).
--
-- Колонка nullable: исторические записи и системные действия без HTTP-контекста
-- (например, фоновые задачи, выполняемые вне веб-запроса) корреляции не имеют.
--
-- Индекс не добавляется: основной паттерн чтения audit_log на сегодня — постраничная
-- выборка с фильтрами по entity_type/action (см. AuditLogQueryRepository), упорядоченная
-- по id. Поиск "все записи аудита по correlationId" пока не реализован ни в одном
-- эндпоинте/use-case; добавлять индекс под гипотетический паттерн доступа — преждевременная
-- оптимизация. Если/когда появится реальный запрос вида
-- "WHERE correlation_id = ?" (например, drill-down из логов в аудит), стоит добавить
-- в отдельной миграции btree-индекс CREATE INDEX CONCURRENTLY на correlation_id.

ALTER TABLE audit_log
    ADD COLUMN correlation_id VARCHAR(64);
