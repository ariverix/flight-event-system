# IMPROVEMENT_PLAN — прогон «промышленный апгрейд» (2026-07-03)

> Сверка «Backlog / follow-up» из `docs/PROGRESS.md` с фактическим кодом.
> Приоритеты: P1 — блокирует промышленную эксплуатацию; P2 — качество/гигиена,
> делаем в этом прогоне; P3 — по возможности. Привязка к фазам прогона (0–8).

## Сверка backlog docs/PROGRESS.md → статус в коде

| Пункт backlog | Статус в коде (2026-07-03) | Приоритет | Фаза |
|---|---|---|---|
| Dedup `startExecution` — unique constraint / claim | **ЖИВ**: `ExecutionService.startExecution` — read-then-write (`existsByDedupKey` → `save`), индекс V23 `idx_exec_dedup_trigger` НЕ уникальный. При 2+ репликах (P6-1 replicas:2 уже в k8s!) возможен двойной старт | **P1** | 1 |
| Гейтинг `@Scheduled` по `ApplicationReadyEvent` | **ЖИВ**: `OutboundMessageDeliveryScheduler.scheduledPoll`, `WaitTimeoutScheduler.pollWaitTimeouts`, `RetentionService.runRetention`, `LeaderElectionService.heartbeat` тикают без гейта готовности | P2 | 1 |
| Мёртвый код `CriterionEvaluator.getCustomFieldValue` | **ЖИВ**: production-вызовов нет (grep: только `CriterionEvaluatorTest` и `P3_2_CustomFieldsEngineScenarioIntTest`) — P3-3 не подхватил. Удалить метод + его тесты | P3 | 1 |
| SpotBugs Medium: 6× CRLF_INJECTION_LOGS + 1× INFORMATION_EXPOSURE | **ЖИВ**: утилиты санитизации в коде нет | **P1** | 3 |
| Rate limiting (чек-лист безопасности) | **ЖИВ**: bucket4j в `backend/pom.xml` отсутствует, лимитеров нет. Брутфорс `/api/v1/auth/**` и флуд открытого `/api/v1/messages/**` ничем не ограничены | **P1** | 3 |
| Default-deny `/api/**` | **ЗАКРЫТ** в P4-1 (SecurityConfig default-deny, PROGRESS.md P4-1) | — | — |
| CLAUDE.md канонические метрики устарели | **ЗАКРЫТ** в фазе 0 этого прогона (санкция Дениса): 23 таблицы, V1–V37, ~843+183 тестов, JaCoCo LINE≥0.88/INSTR≥0.90 | — | 0 |
| Процесс «миграции пишет db-dev» | Процессное замечание, не техдолг. Соблюдаем в этом прогоне (V38+ — db-dev) | N/A | — |
| Посторонний worktree `.claude/worktrees/strange-jang-f38717` | **ЖИВ**: worktree на месте | P3 | 8 |
| P7 aircraft-bindings (TODO из P7-3) | **ЖИВ**: backend-эндпоинта списка бортов нет, UI привязок нет | **P1** | 5–6 |
| P6-3: re-run перфа на тюнингованном пуле / прод-железе | ЖИВ, но требует приёмочный стенд заказчика — вне объёма прогона | P3 | BLOCKERS.md |
| P5-4: флейк-риск DB-chaos тайминга на медленном CI | Наблюдение; проверяется двойным прогоном в фазе 2 | P2 | 2 |

## Пробелы вне backlog (аудит фазы 0 — см. AUDIT.md)

| Пробел | Приоритет | Фаза |
|---|---|---|
| CI: frontend-job с `continue-on-error: true`, vitest (183+ тестов) и lint в CI не гоняются вовсе | **P1** | 7 |
| E2E-тестов нет (ни Playwright, ни иного) — критические потоки не покрыты сквозным тестом | P2 | 6–7 |
| CI: нет job сборки docker-образов и `helm lint` | P2 | 7 |
| Наблюдаемость новых путей (rate limiter, dedup-claim) — по CLAUDE.md п.5 обязательна вместе с фичами | P2 | 4 |
| Ревью индексов горячих запросов (EXPLAIN ANALYZE) после партиционирования V37 | P2 | 5 |
| Frontend ErrorBoundary отсутствует (lazy-роуты без boundary — вводная прогона неточна) | P2 | 6 |
| CORS-origins захардкожены dev-значениями в SecurityConfig — вынести в конфиг | P2 | 3 |
| OpenAPI `docs/openapi/openapi.json` устарел (2026-06-23, контроллеры новее) | P2 | 5 |
| Push docker-образов в registry — нет секрета registry | P3 | BLOCKERS.md |
| OWASP dependency-check локально — нужен NVD_API_KEY | P3 | BLOCKERS.md |

## Порядок исполнения

Фазы 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 строго по плану прогона (`CLAUDE_CODE_PROMPT.md`).
Каждая фаза: TDD → verification loop → reviewer PASS → conventional commit → отчёт в `docs/PROGRESS.md`.
Блокеры — в `BLOCKERS.md`, работа не останавливается.
