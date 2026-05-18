-- ============================================================
-- V11: Демосценарии 2–5 (сценарий 1 в V9)
--
--   2. Контроль связи после посадки   — WAIT → ACTION → RAISE_CONDITION
--   3. Предполётная подготовка        — автостарт на INIT + WAIT подтверждения
--   4. Распределение метеоинформации  — запуск по MESSAGE_RECEIVED, цепочка ACTION
--   5. Уведомление о задержке рейса   — EVALUATE (идемпотентность) → RAISE_CONDITION
--
-- Все в статусе DRAFT. Таймауты 30 сек вместо продакшн-значений.
-- ============================================================

-- СЦЕНАРИЙ 2: Контроль связи после посадки
-- старт: ON, стоп: IN
-- логика: ждём LANDING_REPORT 30 сек → если нет — запрашиваем контакт → поднимаем алерт
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Контроль связи после посадки',
    'Ожидает LANDING_REPORT после посадки. При отсутствии — запрашивает '
    'контакт и поднимает алерт NO_LANDING_CONTACT.',
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


-- СЦЕНАРИЙ 3: Предполётная подготовка
-- старт: INIT (startCriteria = null → автостарт), стоп: OUT
-- логика: шлём чеклист → ждём PREFLIGHT_COMPLETE → алерт если нет ответа
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Предполётная подготовка',
    'Шлёт PREFLIGHT_CHECKLIST при старте рейса, ждёт подтверждения. '
    'Нет ответа — алерт PREFLIGHT_TIMEOUT.',
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


-- СЦЕНАРИЙ 4: Распределение метеоинформации
-- старт: MESSAGE_RECEIVED GROUND/WEATHER_UPDATE, стоп: нет
-- логика: чистая цепочка ACTION без ожидания — пересылка + уведомление + условие
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Распределение метеоинформации',
    'При получении WEATHER_UPDATE пересылает сводку экипажу и диспетчерской.',
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


-- СЦЕНАРИЙ 5: Уведомление о задержке рейса
-- старт: MESSAGE_RECEIVED GROUND/DELAY_NOTICE, стоп: нет
-- EVALUATE на шаге 1 делает сценарий идемпотентным: повторный DELAY_NOTICE не дублирует алерт
INSERT INTO sequences (name, description, status,
                       start_criteria, stop_criteria,
                       created_at, updated_at, created_by)
VALUES (
    'Уведомление о задержке рейса',
    'Поднимает алерт FLIGHT_DELAYED при получении DELAY_NOTICE. '
    'Шаг EVALUATE предотвращает дублирование если алерт уже активен.',
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
