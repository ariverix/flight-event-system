# ADR-0007: Дедупликация startExecution через частичный UNIQUE-индекс БД

**Status:** Accepted

**Date:** 2026-07-03

**Authors:** sequence-engine-dev + db-dev (Фаза 1 прогона «Промышленный апгрейд»), оркестратор

## Context

С P1-7 идемпотентность старта инстанса по `triggering_message_id` была реализована
как **read-then-write** (проверка существования → insert) под неуникальным индексом
V23. Это корректно при одной реплике и последовательной доставке Modulith-событий,
но с HA (P6-1: 2+ реплики, restart-republish из Event Publication Registry) появилось
окно гонки: две реплики (или republish + живая доставка) одновременно проходят
проверку «нет инстанса» и создают **два** инстанса на одно triggering-сообщение.
Backlog-пункт из ревью P1-7 предписывал пересмотреть это в P6-1; пункт остался
открытым — закрыт этим решением.

Прецедент в кодовой базе: single-fire WAIT-таймаутов (P1-5) и outbound-доставки
(P2-3) уже решены атомарным условным UPDATE-claim на уровне БД.

## Decision

Мы переносим гарантию «ровно один старт» на уровень PostgreSQL:

- **V38**: частичный УНИКАЛЬНЫЙ индекс `idx_exec_dedup_trigger_unique` на
  `(sequence_id, aircraft_id, flight_number, triggering_message_id)`
  `NULLS NOT DISTINCT WHERE triggering_message_id IS NOT NULL`
  (заменяет неуникальный `idx_exec_dedup_trigger` из V23; V23 не редактируется —
  применённые миграции неприкосновенны).
- `ExecutionService`: event-driven старт изолируется в `REQUIRES_NEW`
  (`startExecutionInNewTransaction`); проигрыш гонки (SQLState 23505 /
  `DataIntegrityViolationException`) ловится и обрабатывается как **идемпотентный
  no-op** (`startExecutionDeduplicated`) — не ошибка, событие уже обслужено другой
  стороной. Метрика `eca.execution.start.duplicate_rejected` + алерт
  `EcaDuplicateStartRejectedSpike` (аномалия ретрансляции).
- Read-then-write-проверка сохранена как fast-path (дешёвый отказ без исключения),
  но корректность от неё больше НЕ зависит.

## Consequences

**Положительные:**
- Exactly-once старт при любом числе реплик и любом интерливинге republish —
  гарантия инварианта у БД, единственного разделяемого состояния (тот же принцип,
  что P1-5/P2-3/ADR-0004: PostgreSQL как арбитр кластера).
- Восьмипоточный конкурентный тест (`P1_9_ConcurrentStartDedupScenarioIntTest`)
  детерминированно подтверждает: ровно 1 инстанс.

**Отрицательные / принятые риски:**
- `NULLS NOT DISTINCT` требует PostgreSQL 15+ (в стеке 16 — ок; смена СУБД
  потребует переписать индекс).
- Инстансы, стартованные БЕЗ triggering-сообщения (ручной API-старт), индексом
  не покрываются (частичный WHERE) — для них дедуп не нужен по семантике.
- +1 уникальный индекс на горячей таблице — незначимый оверхед вставки.

**Что это требует от команды дальше:**
- При добавлении новых «ровно один раз»-инвариантов следовать тому же паттерну
  (constraint/claim в БД, ловля 23505 как no-op), а не read-then-write.

## Alternatives considered

| Альтернатива | Почему не выбрана |
|---|---|
| Advisory lock PostgreSQL на ключ дедупа | Держит соединение/сессию, семантика сложнее; уникальный индекс декларативен и виден в схеме |
| SELECT ... FOR UPDATE по «таблице ключей» | Отдельная таблица + очистка; индекс решает то же без новых сущностей |
| Кластерный лок через leader election (ADR-0004) | Лидерство гейтит @Scheduled-поллеры, но event-driven старт обязан работать на ВСЕХ репликах — сериализовать его через лидера значит потерять HA-смысл |
| Оставить read-then-write | Некорректно под HA — исходная проблема (backlog P1-7) |

## References

- Миграция: `backend/src/main/resources/db/migration/V38__unique_dedup_index_execution_instances.sql`
- Код: `ExecutionService#startExecutionInNewTransaction/#startExecutionDeduplicated`, `ExecutionMetrics`
- Тесты: `P1_9_ConcurrentStartDedupScenarioIntTest`, `V23TriggeringMessageIdMigrationIntTest` (обновлён под V38)
- ADR-0002 (Outbox/republish), ADR-0004 (PostgreSQL как арбитр), docs/PROGRESS.md Фаза 1
