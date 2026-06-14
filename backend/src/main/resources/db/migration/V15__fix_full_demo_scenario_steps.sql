-- ============================================================
-- V15: Исправление шагов "Полная демонстрация возможностей ECA" (V14)
--
-- Проблемы V14:
--   - ACTION WAIT_TIME при on_success_action=CONTINUE не создаёт
--     реальной паузы: executeTransition сразу переводит instance
--     обратно в RUNNING и выполняет следующий шаг синхронно.
--   - EVALUATE CONDITION_ACTIVE всегда FALSE: RAISE_CONDITION идёt
--     через MessageOutputPort (LogMessageAdapter), а активные условия
--     хранятся в IntegrationService.activeConditions — эти два пути
--     не связаны, поэтому условие никогда не появляется в контексте.
--
-- Решение: заменяем WAIT_TIME на настоящий WAIT (таймаут 6 сек,
-- реально переводит instance в WAITING — видно автообновление),
-- а EVALUATE CONDITION_ACTIVE — на EVALUATE FLIGHT_STAGE (надёжный,
-- не зависит от условий).
-- ============================================================

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Полная демонстрация возможностей ECA' ORDER BY id DESC LIMIT 1;

    DELETE FROM steps WHERE sequence_id = seq_id;

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

    -- ШАГ 3: ACTION RAISE_CONDITION — поднять алерт демо-режима
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

    -- ШАГ 4: WAIT — реальная пауза до 6 сек (или до прихода DEMO_ACK)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 4,
        'Ожидать подтверждение DEMO_ACK (до 6 сек)',
        'WAIT',
        '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK",'
        '"templateName":"DEMO_ACK","fromThisPointOnly":true}',
        6,
        'CONTINUE', TRUE,
        'CONTINUE', FALSE
    );

    -- ШАГ 5: EVALUATE — проверить текущую стадию полёта (надёжная проверка)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 5,
        'Проверить стадию полёта (OFF)',
        'EVALUATE',
        '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"OFF"}',
        NULL,
        'CONTINUE', TRUE,
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

    -- ШАГ 7: ACTION RAISE_CONDITION — зафиксировать завершение демо
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 7,
        'Зафиксировать завершение демо (DEMO_COMPLETE)',
        'ACTION',
        '{"actionType":"RAISE_CONDITION","conditionName":"DEMO_COMPLETE","alertLevel":"INFO"}',
        NULL,
        'END', TRUE,
        'END', TRUE
    );

    -- Сбросить description под новый сценарий
    UPDATE sequences SET description =
        'Демонстрационная цепочка из 7 шагов: SEND_UPLINK, SEND_GROUND, RAISE_CONDITION, '
        'реальный WAIT (до 6 сек, виден автообновляемый статус WAITING), EVALUATE по стадии полёта, '
        'CLOSE_CONDITION и финальный RAISE_CONDITION — для презентаций.',
        updated_at = NOW()
    WHERE id = seq_id;
END $$;
