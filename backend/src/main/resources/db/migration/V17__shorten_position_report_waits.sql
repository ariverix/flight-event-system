-- ============================================================
-- V17: Сокращение демо-таймаутов сценария "Запрос позиционного
-- отчёта после взлёта" (V9/V13) с 30 до 10 секунд для ускорения
-- демонстрации.
-- ============================================================

-- ШАГ 1: ACTION WAIT_TIME — durationSeconds 30 -> 10
UPDATE steps SET
    name = 'Ждать 10 сек после взлёта',
    config = '{"actionType":"WAIT_TIME","durationSeconds":10}'
WHERE sequence_id = (SELECT id FROM sequences WHERE name = 'Запрос позиционного отчёта после взлёта')
  AND order_index = 1;

-- ШАГ 4: WAIT POSITION_REPORT — timeout_seconds 30 -> 10
UPDATE steps SET
    timeout_seconds = 10
WHERE sequence_id = (SELECT id FROM sequences WHERE name = 'Запрос позиционного отчёта после взлёта')
  AND order_index = 4;

-- Обновляем описание последовательности
UPDATE sequences
SET description =
    'Автоматически запрашивает позиционный отчёт через 10 сек после взлёта ВС VP-BQR (рейс SU1234). '
    'Если отчёт не получен — отправляет uplink-запрос и ждёт ответа. '
    'При отсутствии ответа поднимает алерт NO_POSITION_30MIN. '
    'Демосценарий из раздела 1.1.5 дипломной работы.',
    updated_at = NOW()
WHERE name = 'Запрос позиционного отчёта после взлёта';
