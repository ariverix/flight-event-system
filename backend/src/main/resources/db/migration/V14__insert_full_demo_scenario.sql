-- ============================================================
-- V14: Полная демонстрация возможностей ECA
--
-- Один сценарий, показывающий все типы шагов и действий подряд:
--   1. ACTION SEND_UPLINK     — приветствие экипажу
--   2. ACTION SEND_GROUND     — уведомление диспетчерской
--   3. ACTION RAISE_CONDITION — поднять алерт DEMO_MODE
--   4. ACTION WAIT_TIME       — пауза 5 сек
--   5. EVALUATE CONDITION_ACTIVE — проверить DEMO_MODE (true → продолжаем)
--   6. ACTION CLOSE_CONDITION — снять алерт DEMO_MODE
--   7. WAIT MESSAGE_RECEIVED  — ждать DEMO_ACK, таймаут 7 сек
--   8. ACTION RAISE_CONDITION — алерт DEMO_NO_ACK при таймауте
--
-- Старт: FLIGHT_STAGE = OFF. Статус DRAFT — активировать через UI.
-- ============================================================

INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Полная демонстрация возможностей ECA',
    'Демонстрационная цепочка из 8 шагов: задействует все типы ACTION '
    '(SEND_UPLINK, SEND_GROUND, RAISE_CONDITION, CLOSE_CONDITION, WAIT_TIME), '
    'шаг EVALUATE и шаг WAIT с коротким таймаутом — для презентаций.',
    'DRAFT',
    '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"OFF"}',
    '{"type":"FLIGHT_STAGE","operator":"GREATER_OR_EQUAL","targetStage":"ON"}',
    NOW(), NOW(), 1
);

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Полная демонстрация возможностей ECA' ORDER BY id DESC LIMIT 1;

    -- ШАГ 1: ACTION SEND_UPLINK — приветствие экипажу
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Отправить приветствие экипажу (DEMO_GREETING)',
        'ACTION',
        '{"actionType":"SEND_UPLINK","templateName":"DEMO_GREETING","params":{"mode":"presentation"}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- ШАГ 2: ACTION SEND_GROUND — уведомить диспетчерскую
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Уведомить диспетчерскую (DEMO_DISPATCH_NOTIFY)',
        'ACTION',
        '{"actionType":"SEND_GROUND","templateName":"DEMO_DISPATCH_NOTIFY","params":{"source":"DEMO"}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- ШАГ 3: ACTION RAISE_CONDITION — активировать демо-режим
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Поднять алерт DEMO_MODE',
        'ACTION',
        '{"actionType":"RAISE_CONDITION","conditionName":"DEMO_MODE","alertLevel":"INFO"}',
        NULL,
        'CONTINUE', TRUE,
        'END',      FALSE
    );

    -- ШАГ 4: ACTION WAIT_TIME — пауза 5 сек
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 4,
        'Технологическая пауза 5 сек',
        'ACTION',
        '{"actionType":"WAIT_TIME","durationSeconds":5}',
        NULL,
        'CONTINUE', FALSE,
        'END',      FALSE
    );

    -- ШАГ 5: EVALUATE — проверить, активен ли DEMO_MODE
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 5,
        'Проверить: алерт DEMO_MODE активен?',
        'EVALUATE',
        '{"type":"CONDITION_ACTIVE","conditionName":"DEMO_MODE"}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- ШАГ 6: ACTION CLOSE_CONDITION — снять алерт демо-режима
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 6,
        'Снять алерт DEMO_MODE',
        'ACTION',
        '{"actionType":"CLOSE_CONDITION","conditionName":"DEMO_MODE"}',
        NULL,
        'CONTINUE', TRUE,
        'END',      FALSE
    );

    -- ШАГ 7: WAIT — ждать подтверждение DEMO_ACK, таймаут 7 сек
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 7,
        'Ожидать подтверждение DEMO_ACK',
        'WAIT',
        '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK",'
        '"templateName":"DEMO_ACK","fromThisPointOnly":true}',
        7,
        'END',      TRUE,
        'CONTINUE', FALSE
    );

    -- ШАГ 8: ACTION RAISE_CONDITION — алерт об отсутствии подтверждения (по таймауту)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 8,
        'Поднять алерт DEMO_NO_ACK (нет подтверждения)',
        'ACTION',
        '{"actionType":"RAISE_CONDITION","conditionName":"DEMO_NO_ACK","alertLevel":"WARNING"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );
END $$;
