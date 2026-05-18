-- ============================================================
-- V9: Запрос позиционного отчёта после взлёта
--
-- Типичная проблема: борт взлетел, но 15 минут нет POS-репорта.
-- Система сама запрашивает позицию и поднимает алерт если нет ответа.
--
-- Шаги:
--   1. WAIT_TIME 30 сек  (прод: 900 сек = 15 мин)
--   2. EVALUATE: есть ли POS-репорт за последние 30 мин?
--   3. SEND_UPLINK REQUEST_POSITION
--   4. WAIT POSITION_REPORT, timeout 30 сек  (прод: 600 сек = 10 мин)
--   5. RAISE_CONDITION NO_POSITION_30MIN
--
-- Статус DRAFT — нужно активировать через UI.
-- ============================================================

INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Запрос позиционного отчёта после взлёта',
    'Если после взлёта нет POS-репорта — запрашивает позицию uplink. '
    'При отсутствии ответа поднимает алерт NO_POSITION_30MIN.',
    'DRAFT',
    '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"OFF"}',
    '{"type":"FLIGHT_STAGE","operator":"GREATER_OR_EQUAL","targetStage":"ON"}',
    NOW(), NOW(), 1
);

-- Сохранить ID для ссылок в шагах
DO $$
DECLARE
    seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id
    FROM sequences
    WHERE name = 'Запрос позиционного отчёта после взлёта'
    ORDER BY id DESC
    LIMIT 1;

    -- ШАГ 1: ACTION WAIT_TIME — ждать 30 сек после взлёта
    -- (в продакшене: durationSeconds = 900 = 15 мин)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Ждать 30 сек после взлёта',
        'ACTION',
        '{"actionType":"WAIT_TIME","durationSeconds":30}',
        NULL,
        'CONTINUE', FALSE,
        'END',      FALSE
    );

    -- ШАГ 2: EVALUATE — получен ли позиционный отчёт за последние 30 мин?
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Проверить наличие позиционного отчёта',
        'EVALUATE',
        '{"type":"POSITION_REPORTED","minutesAgo":30}',
        NULL,
        'END',      FALSE,
        'CONTINUE', FALSE
    );

    -- ШАГ 3: ACTION SEND_UPLINK — отправить запрос позиции
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Отправить uplink REQUEST_POSITION',
        'ACTION',
        '{"actionType":"SEND_UPLINK","templateName":"REQUEST_POSITION","params":{}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- ШАГ 4: WAIT — ожидать POSITION_REPORT, timeout 30 сек, fromThisPointOnly=true
    -- (в продакшене: timeoutSeconds = 600 = 10 мин)
    -- fromThisPointOnly=true — учитывать только сообщения после начала ожидания
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 4,
        'Ожидать POSITION_REPORT',
        'WAIT',
        '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK",'
        '"templateName":"POSITION_REPORT","fromThisPointOnly":true}',
        30,
        'END',      FALSE,
        'CONTINUE', TRUE
    );

    -- ШАГ 5: ACTION RAISE_CONDITION — поднять алерт об отсутствии позиции
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 5,
        'Поднять алерт NO_POSITION_30MIN',
        'ACTION',
        '{"actionType":"RAISE_CONDITION",'
        '"conditionName":"NO_POSITION_30MIN","alertLevel":"HIGH"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );

END $$;
