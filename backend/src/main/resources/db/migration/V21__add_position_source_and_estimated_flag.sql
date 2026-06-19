-- Паритет с SITA Sequencer для POSITION-критерия:
-- POSITION-критерий должен (1) знать источник позиционного отчёта (ACARS/RADAR/ADS_B)
-- и (2) игнорировать оценочные (estimated) позиции — учитываются только фактические.
--
-- position_source nullable: заполняется только для сообщений, являющихся позиционными
-- отчётами; для прочих ACARS-сообщений (STATUS, CLEARANCE и т.п.) остаётся NULL.
--
-- is_estimated_position NOT NULL DEFAULT FALSE: консервативный дефолт — исторические
-- записи и сообщения без явной пометки считаются фактическими (actual), а не оценочными,
-- чтобы не "потерять" уже учтённые в критериях позиции при миграции существующих данных.

ALTER TABLE messages
    ADD COLUMN position_source VARCHAR(20),
    ADD COLUMN is_estimated_position BOOLEAN NOT NULL DEFAULT FALSE;

-- POSITION-критерий ищет последний фактический (не estimated) позиционный отчёт по ВС
-- в скользящем окне — индекс ускоряет частый запрос "максимум receivedAt с фильтром".
CREATE INDEX idx_messages_position_lookup
    ON messages (aircraft_id, is_estimated_position, received_at)
    WHERE position_source IS NOT NULL;
