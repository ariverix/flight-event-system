-- ============================================================
-- V16: Исправление шага 5 (EVALUATE) демо-сценария "Полная демонстрация
-- возможностей ECA" (V14/V15).
--
-- Проблема V15: EVALUATE FLIGHT_STAGE EQUALS OFF читает
-- context.currentFlightStage(), который заполняется ТОЛЬКО для событий
-- смены стадии полёта. При возобновлении WAIT-шага через входящее
-- сообщение (DEMO_ACK) или через scheduled checkWaitTimeouts()
-- currentFlightStage() == null → evaluateFlightStage всегда FALSE →
-- on_failure_action=END обрывает выполнение на шаге 5.
--
-- Решение: критерий заменён на MESSAGE_RECEIVED DEMO_ACK (без
-- fromThisPointOnly — проверяем, пришло ли подтверждение вообще),
-- что не зависит от контекста события. Обе ветки (success/failure)
-- ведут на CONTINUE, чтобы EVALUATE был демонстрационным и не обрывал
-- сценарий — шаги 6 и 7 выполняются в любом случае.
-- ============================================================

UPDATE steps SET
    name = 'Проверить: подтверждение DEMO_ACK получено?',
    config = '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK","templateName":"DEMO_ACK"}',
    on_success_action = 'CONTINUE', on_success_notify = TRUE,
    on_failure_action = 'CONTINUE', on_failure_notify = TRUE
WHERE sequence_id = (SELECT id FROM sequences WHERE name = 'Полная демонстрация возможностей ECA' ORDER BY id DESC LIMIT 1)
  AND order_index = 5;
