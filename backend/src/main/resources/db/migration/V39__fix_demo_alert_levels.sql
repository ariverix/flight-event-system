-- V39: бэкфилл alertLevel в демо-сценариях (INFO/WARNING → канонические LOW/MEDIUM).
--
-- Уже применённые миграции V11/V14/V15 вставили RAISE_CONDITION-шаги с alertLevel=INFO/WARNING —
-- значениями старого legacy-UI (frontend StepForm.tsx), которых НЕТ в backend-enum AlertLevel
-- (NO/LOW/MEDIUM/HIGH/CRITICAL, см. sequence/domain/AlertLevel.java). ActionStepRule.execute
-- ловит IllegalArgumentException из AlertLevel.valueOf(...) и возвращает StepResult.FAILURE —
-- то есть при выполнении канонического демо-сценария (борт VP-BQR, рейс SU1234, CLAUDE.md) шаги
-- RAISE_CONDITION для NO_LANDING_CONTACT / WEATHER_ADVISORY_SENT / FLIGHT_DELAYED / DEMO_MODE /
-- DEMO_COMPLETE падают вместо того, чтобы поднять условие. (V14 также вставляла DEMO_NO_ACK с
-- alertLevel=WARNING, но V15 полностью удаляет и пересобирает шаги той же demo-последовательности
-- без этого условия — в текущих данных к моменту этой миграции DEMO_NO_ACK уже не существует.)
--
-- Применённые миграции не трогаем (правило проекта) — правим данные новой миграцией.
-- Маппинг сохраняет относительный порядок серьёзности старой 3-уровневой шкалы:
--   INFO (информационный)  → LOW
--   WARNING (предупреждение) → MEDIUM
-- CRITICAL уже входит в канонический enum — не трогаем (в демо-данных встречается,
-- например PREFLIGHT_TIMEOUT в V11).

UPDATE steps
SET config = jsonb_set(config, '{alertLevel}', '"LOW"')
WHERE config ->> 'alertLevel' = 'INFO';

UPDATE steps
SET config = jsonb_set(config, '{alertLevel}', '"MEDIUM"')
WHERE config ->> 'alertLevel' = 'WARNING';
