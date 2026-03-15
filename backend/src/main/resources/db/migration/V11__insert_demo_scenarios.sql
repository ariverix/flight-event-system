-- ============================================================
-- V11: Демонстрационные сценарии для защиты дипломной работы
--
-- Добавляет 4 дополнительных сценария (итого 5 вместе с V9):
--   Сценарий 1 (V9): Запрос позиционного отчёта после взлёта
--   Сценарий 2: Контроль связи после посадки
--   Сценарий 3: Предполётная подготовка
--   Сценарий 4: Распределение метеоинформации
--   Сценарий 5: Уведомление о задержке рейса
--
-- Все сценарии в статусе DRAFT — оператор активирует через UI.
-- Тайм-ауты сокращены для демонстрации (30 сек вместо продакшн-значений).
-- ============================================================

-- ============================================================
-- СЦЕНАРИЙ 2: Контроль связи после посадки
-- Запуск: FlightStage = ON (посадка)
-- Остановка: FlightStage = IN (заруливание)
-- Демонстрирует: WAIT с тайм-аутом → ACTION → RAISE_CONDITION
-- UC-07, UC-08
-- ============================================================
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Контроль связи после посадки',
    'После посадки ожидает доклад о прибытии в течение 30 секунд. '
    'Если доклад не получен — запрашивает контакт и поднимает алерт. '
    'Демонстрирует UC-08 (обработка тайм-аута WAIT-шага).',
    'DRAFT',
    '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"ON"}',
    '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"IN"}',
    NOW(), NOW(), 1
);

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Контроль связи после посадки' ORDER BY id DESC LIMIT 1;

    -- Шаг 1: WAIT — ждать доклад о прибытии (30 сек тайм-аут)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Ожидать доклад о прибытии (LANDING_REPORT)',
        'WAIT',
        '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK",'
        '"templateName":"LANDING_REPORT","fromThisPointOnly":true}',
        30,
        'END',      TRUE,
        'CONTINUE', FALSE
    );

    -- Шаг 2: ACTION — запросить контакт с экипажем
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Запросить контакт с экипажем',
        'ACTION',
        '{"actionType":"SEND_GROUND","templateName":"CONTACT_REQUEST",'
        '"params":{"priority":"HIGH"}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- Шаг 3: ACTION — поднять алерт об отсутствии связи
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Поднять алерт NO_LANDING_CONTACT',
        'ACTION',
        '{"actionType":"RAISE_CONDITION",'
        '"conditionName":"NO_LANDING_CONTACT","alertLevel":"WARNING"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );
END $$;


-- ============================================================
-- СЦЕНАРИЙ 3: Предполётная подготовка
-- Запуск: FlightStage = INIT (автозапуск при начале рейса)
-- Остановка: FlightStage = OUT (выруливание)
-- Демонстрирует: автостарт на INIT, ACTION + WAIT + RAISE_CONDITION
-- UC-06 (автоматическая реакция на событие OOOI)
-- ============================================================
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Предполётная подготовка',
    'Автоматически запускается при начале рейса (FlightStage=INIT). '
    'Направляет предполётный чеклист экипажу и ожидает подтверждения. '
    'При отсутствии ответа — поднимает алерт PREFLIGHT_TIMEOUT. '
    'Демонстрирует UC-06: автоматическую реакцию на начало рейса.',
    'DRAFT',
    NULL,
    '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"OUT"}',
    NOW(), NOW(), 1
);

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Предполётная подготовка' ORDER BY id DESC LIMIT 1;

    -- Шаг 1: ACTION — отправить предполётный чеклист
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Отправить предполётный чеклист экипажу',
        'ACTION',
        '{"actionType":"SEND_UPLINK","templateName":"PREFLIGHT_CHECKLIST",'
        '"params":{"version":"2.1"}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- Шаг 2: WAIT — ждать подтверждение (30 сек для демо)
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Ожидать подтверждение чеклиста',
        'WAIT',
        '{"type":"MESSAGE_RECEIVED","messageType":"DOWNLINK",'
        '"templateName":"PREFLIGHT_COMPLETE","fromThisPointOnly":true}',
        30,
        'END',      TRUE,
        'CONTINUE', FALSE
    );

    -- Шаг 3: ACTION — алерт об отсутствии ответа
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Алерт: предполётный чеклист не подтверждён',
        'ACTION',
        '{"actionType":"RAISE_CONDITION",'
        '"conditionName":"PREFLIGHT_TIMEOUT","alertLevel":"CRITICAL"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );
END $$;


-- ============================================================
-- СЦЕНАРИЙ 4: Распределение метеоинформации
-- Запуск: получено сообщение WEATHER_UPDATE (тип GROUND)
-- Остановка: нет (завершается сам)
-- Демонстрирует: запуск по сообщению + мгновенное выполнение ACTION
-- UC-06 (мгновенная цепочка без ожидания)
-- ============================================================
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Распределение метеоинформации',
    'Запускается при получении наземной метеосводки (WEATHER_UPDATE). '
    'Немедленно ретранслирует данные экипажу uplink-сообщением '
    'и уведомляет диспетчерскую. Завершается мгновенно. '
    'Демонстрирует UC-06: автоматическую цепочку ACTION-шагов.',
    'DRAFT',
    '{"type":"MESSAGE_RECEIVED","messageType":"GROUND","templateName":"WEATHER_UPDATE"}',
    NULL,
    NOW(), NOW(), 1
);

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Распределение метеоинформации' ORDER BY id DESC LIMIT 1;

    -- Шаг 1: ACTION — переслать метеосводку экипажу
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Переслать метеосводку экипажу (WEATHER_ADVISORY)',
        'ACTION',
        '{"actionType":"SEND_UPLINK","templateName":"WEATHER_ADVISORY"}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- Шаг 2: ACTION — уведомить диспетчерскую службу
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Уведомить диспетчерскую (DISPATCH_NOTIFY)',
        'ACTION',
        '{"actionType":"SEND_GROUND","templateName":"DISPATCH_NOTIFY",'
        '"params":{"source":"WEATHER_SYSTEM"}}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- Шаг 3: ACTION — зафиксировать условие
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Установить условие WEATHER_ADVISORY_SENT',
        'ACTION',
        '{"actionType":"RAISE_CONDITION",'
        '"conditionName":"WEATHER_ADVISORY_SENT","alertLevel":"INFO"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );
END $$;


-- ============================================================
-- СЦЕНАРИЙ 5: Уведомление о задержке рейса
-- Запуск: получено сообщение DELAY_NOTICE (тип GROUND)
-- Остановка: нет
-- Демонстрирует: EVALUATE → условная ветка → END или RAISE_CONDITION
-- UC-06, UC-07 (условное выполнение)
-- ============================================================
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Уведомление о задержке рейса',
    'Запускается при получении уведомления о задержке (DELAY_NOTICE). '
    'Проверяет — не выслан ли уже аналогичный алерт — '
    'и при необходимости поднимает условие FLIGHT_DELAYED. '
    'Демонстрирует UC-07: условное ветвление EVALUATE-шага.',
    'DRAFT',
    '{"type":"MESSAGE_RECEIVED","messageType":"GROUND","templateName":"DELAY_NOTICE"}',
    NULL,
    NOW(), NOW(), 1
);

DO $$
DECLARE seq_id BIGINT;
BEGIN
    SELECT id INTO seq_id FROM sequences
    WHERE name = 'Уведомление о задержке рейса' ORDER BY id DESC LIMIT 1;

    -- Шаг 1: EVALUATE — проверить, активен ли уже алерт FLIGHT_DELAYED
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 1,
        'Проверить: алерт FLIGHT_DELAYED уже активен?',
        'EVALUATE',
        '{"type":"CONDITION_ACTIVE","conditionName":"FLIGHT_DELAYED"}',
        NULL,
        'END',      FALSE,
        'CONTINUE', FALSE
    );

    -- Шаг 2: ACTION — подтвердить получение уведомления о задержке
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 2,
        'Подтвердить получение уведомления',
        'ACTION',
        '{"actionType":"SEND_GROUND","templateName":"DELAY_ACKNOWLEDGED"}',
        NULL,
        'CONTINUE', FALSE,
        'END',      TRUE
    );

    -- Шаг 3: ACTION — поднять алерт о задержке
    INSERT INTO steps (sequence_id, order_index, name, step_type,
                       config, timeout_seconds,
                       on_success_action, on_success_notify,
                       on_failure_action, on_failure_notify)
    VALUES (
        seq_id, 3,
        'Поднять алерт FLIGHT_DELAYED',
        'ACTION',
        '{"actionType":"RAISE_CONDITION",'
        '"conditionName":"FLIGHT_DELAYED","alertLevel":"WARNING"}',
        NULL,
        'END', TRUE,
        'END', FALSE
    );
END $$;
