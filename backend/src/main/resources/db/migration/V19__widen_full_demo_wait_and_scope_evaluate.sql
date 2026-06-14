-- ============================================================
-- V19: Презентационный сценарий ECA (sequence id=7)
--
-- Проблема: шаг 4 (WAIT DEMO_ACK, timeout 6 сек) часто завершался
-- FAILURE по таймауту, потому что DEMO_ACK от фронтенда (DemoPage,
-- followUp через ~4 сек + задержка опроса) приходил впритык или
-- позже. При этом шаг 5 (EVALUATE MESSAGE_RECEIVED DEMO_ACK без
-- fromThisPointOnly) находил ЛЮБОЕ DEMO_ACK из истории и всегда
-- возвращал SUCCESS — визуально получалось "шаг 4: ошибка, шаг 5:
-- успех, итог: завершено успешно", что выглядит нелогично.
--
-- Решение:
--  1. Увеличиваем таймаут WAIT с 6 до 10 сек — даёт запас для
--     followUp(4 сек) + сетевых задержек.
--  2. EVALUATE теперь проверяет DEMO_ACK с fromThisPointOnly=true
--     (используется waitStartedAt текущего выполнения, см.
--     EvaluateStepRule) — результат относится к ЭТОМУ запуску,
--     а не к истории.
-- ============================================================

UPDATE steps SET timeout_seconds = 10
WHERE sequence_id = (SELECT id FROM sequences WHERE name = 'Презентационный сценарий ECA')
  AND order_index = 4;

UPDATE steps SET
    config = '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK","templateName":"DEMO_ACK","fromThisPointOnly":true}'
WHERE sequence_id = (SELECT id FROM sequences WHERE name = 'Презентационный сценарий ECA')
  AND order_index = 5;
