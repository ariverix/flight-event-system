-- P2-1: идемпотентность входящего ACARS-шлюза по идентификатору сообщения от внешней
-- системы (ARINC message reference, AFTN serial number и т.п.). messages — таблица из V6,
-- демо-данные туда не вставляются ни в одной миграции (V9/V14 заполняют sequences/steps/
-- execution_instances, не messages), поэтому добавление колонки и построение partial unique
-- индекса безопасно на пустой/малой таблице — без долгих локов на проде.
ALTER TABLE messages
    ADD COLUMN external_message_id VARCHAR(255) NULL;

-- UNIQUE только среди непустых значений: NULL разрешён многократно (legacy-источники
-- без надёжного message reference), повторный непустой externalMessageId невозможен —
-- это дедуп-гарантия идемпотентности входящего шлюза (P2-1).
CREATE UNIQUE INDEX idx_messages_external_message_id_unique
    ON messages (external_message_id)
    WHERE external_message_id IS NOT NULL;
