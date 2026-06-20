# PROGRESS — ledger выполнения RUN_PLAN.md

> Ведётся автономно: после каждой закрытой задачи добавляется/обновляется
> строка. Источник формулировок задач — `RUN_PLAN.md`. Не переписывать
> историю прошлых строк задним числом — только добавлять новые записи
>/менять статус и колонку "Доказательство" по факту.
>
> Статусы: `Pending` → `In progress` → `Done` (или `Blocked`, если задача
> застряла — с указанием причины в "Доказательство").

## P0 — Фундамент

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P0-1 | CI/CD каркас в GitHub Actions: build → `./gradlew test` → JaCoCo coverage gate ≥85% → шаг Modulith-verify | devops-agent + architect | Done | Коммит `42aabec` ("P0 guardrails"), reviewer PASS. JaCoCo gate baseline LINE≥0.88 / INSTR≥0.90, Modulith-verify в CI. |
| P0-2 | Тест границ модулей `ApplicationModules.of(App).verify()`, починка нарушений | architect + backend-dev | Done | Коммит `42aabec`, reviewer PASS. Тест зелёный, нарушений границ не найдено. |
| P0-3 | springdoc + OpenAPI на текущее API + Swagger UI | docs-agent | Done | Коммит `42aabec`, reviewer PASS. `docs/openapi/openapi.json` сгенерирован, Swagger UI подключён. |
| P0-4 | Структурные JSON-логи + correlationId сквозным фильтром; каркас `audit_log` (новая миграция) | observability-agent + db-dev | Done | Коммит `42aabec`, reviewer PASS. Spring Boot 3.5 ECS JSON-логи, correlationId-фильтр с whitelist-валидацией, миграция V20 (`audit_log.correlation_id`). |
| P0-5 | `docs/adr/` + ADR-0001 «модульный монолит vs микросервисы»; `docs/PROGRESS.md` ledger | architect + tech-lead | Done | reviewer PASS. `docs/adr/ADR-0001-*.md`, `ADR-template.md`, `README.md`, `docs/PROGRESS.md`. Коммит завершения P0. |
| P0-6 | Threat model (черновик) + чек-лист требований ФСТЭК + license report зависимостей в CI | security-agent + compliance-agent | Done | reviewer PASS. `docs/security/threat-model.md` (STRIDE, R-1..R-9), `docs/security/fstec-checklist.md`, license-maven-plugin (`aggregate-third-party-report`) + CI artifact. Коммит завершения P0. |

## P1 — Промышленное ядро движка

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P1-1 | Паритет SITA: 3 типа шагов (ACTION/EVALUATE/WAIT), 6 типов критериев с операторами и AND/OR | sequence-engine-dev | Done | reviewer PASS (1 цикл bug-fixer: position_source fallback). Все типы шагов/критериев, операторы, AND/OR nested, from-this-point-only, estimated-ignore. Миграция V21. 292 теста зелёные. |
| P1-2 | Решения CONTINUE/GOTO/END/ABORT (true/false) + Notify; start/stop-критерии непрерывной оценки | sequence-engine-dev | Done | reviewer PASS. Независимые true/false решения, GOTO вперёд/назад, Notify, start/stop непрерывной оценки (схема V2/V3 уже поддерживала). Починены 2 бага: бесконечный синхронный GOTO-цикл (loop guard 1000→ABORT), устаревший WAIT-таймаут при повторном входе. 307 тестов. |
| P1-3 | Персистентный стейт инстанса: таблица `sequence_instance` + миграция + репозиторий | db-dev + sequence-engine-dev | Done | reviewer PASS. `execution_instances`(=sequence_instance) уже имела шаг/статус/таймауты/context; V22 +updated_at +version(nullable, задел P1-6). Механизм InstanceContext/Codec, save-on-every-transition. Починен баг: from-this-point-only reference переживает очистку waitStartedAt и рестарт (готово к P1-4). OpenAPI обновлён. 321 тест. |
| P1-4 | Resume после рестарта незавершённых инстансов | sequence-engine-dev + test-engineer | Done | reviewer PASS (1 цикл bug-fixer: транзакционная изоляция — REQUIRES_NEW на инстанс, reload-by-id от detached/LazyInit, тест с PG-триггером). `ExecutionResumeRunner` (ApplicationRunner на ApplicationReadyEvent) сканирует RUNNING/WAITING при старте; WAITING восстанавливается "бесплатно" (WAIT-окно и from-this-point-only уже персистентны с P1-3, читаются заново слушателем/scheduler); RUNNING докручивается повторным детерминированным прогоном текущего шага (`ExecutionService#resumeRunningInstanceAfterRestart`). Гарантия сейчас — at-least-once (не exactly-once для ACTION с внешним эффектом) — усиливается Outbox в P1-7. Без миграции (V22 уже всё нужное персистит). Multi-replica/leader election — зона P6-1, не реализовано. 337 тестов.
| P1-5 | Durable-планировщик WAIT/таймаутов (БД-backed) | sequence-engine-dev | Done | reviewer PASS. Single-fire через атомарный условный UPDATE-claim (`wait_timeout_at=NULL WHERE id AND status AND wait_timeout_at=expected`), REQUIRES_NEW, без ShedLock/Quartz и без миграции (импортозамещение). WaitTimeoutScheduler (@Scheduled, тонкий). Переживает рестабт. Конкурентный тест 8 потоков на реальном Postgres. leader election не нужен для корректности. 343 теста. |
| P1-6 | Конкурентность: оптимистические блокировки, без гонок на много инстансов | sequence-engine-dev | Done | reviewer PASS. `@Version` на ExecutionInstance (колонка из V22, без новой миграции); bounded-retry (5) на ObjectOptimisticLockingFailureException с перечитыванием в REQUIRES_NEW; фан-аут event→много инстансов: per-instance REQUIRES_NEW через self-proxy (снята классовая @Transactional). Конкурентные микротесты 10-25 инстансов/потоков на реальном Postgres. 349 тестов. |
| P1-7 | Transactional Outbox (`event_publication`) + идемпотентный приём + ADR Outbox vs прямой вызов | architect + sequence-engine-dev + db-dev | Done | reviewer PASS. ADR-0002 (Outbox через Spring Modulith Event Publication Registry vs прямой вызов; исключение ActionStepRule→MessageOutputPort синхронно). Флаги republish-on-restart + completion-mode=update (сверены с metadata 1.3.1). Идемпотентность startExecution через V23 `triggering_message_id` + dedup-индекс. 8 тестов (republish через настоящий Modulith API, dedup, atomicity). 361 тест. Follow-up: см. backlog. |
| P1-8 | Event Log класса Tracking: завершение шага / старт-стоп последовательности | observability-agent | Done | reviewer PASS. V24: `sequences.logging_enabled` (DEFAULT TRUE) + таблица `tracking_event_log` (+4 индекса). Запись SEQUENCE_STARTED/STEP_COMPLETED/SEQUENCE_STOPPED/SEQUENCE_ABORTED в ExecutionService, gated по флагу (через SequenceQueryPort, границы целы), в одной tx с переходом, correlationId. Идемпотентность опирается на P1-6/P1-7. 375 тестов. |

## P2 — Интеграция ACARS

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P2-1 | Входящий шлюз ACARS: приём → нормализация → `MessageReceived`, идемпотентность по ID сообщения | integration-dev + db-dev | Done | reviewer PASS (1 цикл bug-fixer: TOCTOU-гонка). Идемпотентность шлюза по `externalMessageId` (persist-before-process, find-before-save), V25 `messages.external_message_id` + partial UNIQUE. Гонка: saveAndFlush+catch DataIntegrityViolation+recovery-read REQUIRES_NEW (graceful, без 500); 8-поточный тест на реальном Postgres. Дополняет идемпотентность потребителя P1-7. 397 тестов. |
| P2-2 | Парсеры ARINC 620/618 / Type B / AFTN | integration-dev | Done | reviewer PASS (правка: openapi обновлён). Парсеры ARINC 618/620/Type B/AFTN (integration.parser), raw-эндпоинт `/messages/incoming/raw` в integration (вызов eventprocessor через port-in NamedInterface — цикл границ разрешён). Извлечение tail/flight/type/payload/externalMessageId, AN/FI паритет. Тесты на реальных примерах + негатив. Без миграции. 443 теста. |
| P2-3 | Исходящий шлюз: uplink/ground через Outbox | integration-dev | Pending | — |
| P2-4 | Позывные + таблица `callsign_matching` → определение FI | integration-dev + db-dev | Pending | — |
| P2-5 | Источники позиций ACARS/ADS-B/radar, фактические vs оценочные | integration-dev | Pending | — |
| P2-6 | DLQ + ручной reprocess + ретраи/backoff + circuit breaker | integration-dev | Pending | — |
| P2-7 | Нагрузочный замер входящего потока (k6/Gatling), отчёт `docs/perf/` | test-engineer | Pending | — |

## P3 — Шаблоны / поля / алерты / уведомления

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P3-1 | Движок шаблонов downlink/uplink/ground, computer-generated/external-user, CRUD + OpenAPI | templates-dev | Pending | — |
| P3-2 | Движок custom fields: извлечение, хранение per-flight, подстановка | templates-dev + db-dev | Pending | — |
| P3-3 | Условия/алерты raise/close, уровни No/Low/Medium/High/Critical, авто-закрытие | alerts-dev | Pending | — |
| P3-4 | Event Handling (folder + sequence override) + Notify-каналы идемпотентно | alerts-dev + db-dev | Pending | — |

## P4 — Безопасность

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P4-1 | RBAC user-rights (роли→права→проверки на эндпоинтах) | security-agent + db-dev | Pending | — |
| P4-2 | JWT доступа + refresh с ротацией, BCrypt; ADR токенов/крипто | security-agent | Pending | — |
| P4-3 | Вынос секретов из кода, чистка логов, путь к ГОСТ TLS | security-agent + devops-agent | Pending | — |
| P4-4 | OWASP Dependency-Check + SAST в CI, фейл на High/Critical | security-agent + devops-agent | Pending | — |
| P4-5 | Полный audit_log действий пользователя | security-agent + db-dev | Pending | — |

## P5 — Наблюдаемость и надёжность

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P5-1 | Метрики Micrometer→Prometheus (инстансы, msg/s, ошибки, DLQ, латентность, БД, JVM) | observability-agent | Pending | — |
| P5-2 | Трассировка OpenTelemetry сквозная с корреляцией борт/рейс/инстанс | observability-agent | Pending | — |
| P5-3 | Health liveness/readiness/startup + дашборды Grafana + SLO как код | observability-agent | Pending | — |
| P5-4 | Бэкап/восстановление БД + chaos/failover тесты | db-dev + devops-agent + test-engineer | Pending | — |

## P6 — Масштаб

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P6-1 | HA: реплики backend + leader election планировщика (single-fire в кластере) | devops-agent | Pending | — |
| P6-2 | Горизонтальное масштабирование + партиционирование/retention больших таблиц | devops-agent + db-dev | Pending | — |
| P6-3 | Полный нагрузочный прогон + профилирование + тюнинг, отчёт p95/p99 | test-engineer | Pending | — |

## P7 — Промышленный фронтенд

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P7-1 | Каркас FE: слои, стор, API-клиент из OpenAPI, WebSocket-слой; ADR фронтенда | frontend-architect | Pending | — |
| P7-2 | Редактор React Flow: ноды шагов, рёбра-решения, drag-n-drop GOTO, start/stop-критерии | ui-agent | Pending | — |
| P7-3 | Формы шагов и критериев, шаблоны, алерты, привязка к бортам, валидация | ui-agent | Pending | — |
| P7-4 | Реал-тайм статусы инстансов + Event Log через WebSocket + таймлайн | ui-agent | Pending | — |
| P7-5 | i18n RU/EN, роль-зависимый UI, a11y, скелетоны/анимации | frontend-architect + ui-agent | Pending | — |

## P8 — Упаковка, деплой, доки, приёмка

| ID | Описание | Ответственный агент | Статус | Доказательство |
|---|---|---|---|---|
| P8-1 | Kubernetes/Helm чарты, пробы, ConfigMap/Secret, rolling/blue-green, Flyway при деплое | devops-agent | Pending | — |
| P8-2 | Руководство администратора (RU) + ранбуки эксплуатации + README | docs-agent | Pending | — |
| P8-3 | Матрица паритета SITA→наше→UAT + license report + материалы для Реестра российского ПО | compliance-agent | Pending | — |
| P8-4 | Приёмочный прогон по матрице паритета + финальный отчёт готовности | tech-lead + compliance-agent | Pending | — |

## Сводные метрики на момент последнего обновления

- Тестов: 443 зелёных.
- Последняя миграция: V25 (`messages.external_message_id` + partial UNIQUE).
- **Фаза P1 завершена** (P1-1..P1-8 все reviewer-PASS). P2 в работе.

## Backlog / follow-up (отложенные, зафиксированы при ревью)

- **Default-deny в SecurityConfig** (из P0-3 ревью): цепочка матчеров заканчивается `.anyRequest().permitAll()` (default-allow) — будущий контроллер без явного matcher окажется открытым. Закрыть в P4-1 (RBAC).
- **Dedup startExecution — unique constraint / claim-механизм** (из P1-7 ревью): сейчас дедуп read-then-write без unique-constraint; безопасно при текущей последовательной at-least-once семантике Modulith (single-node, restart-republish), но при нескольких репликах backend (P6-1) или scheduled-retry с параллельным опросом потребуется unique constraint или claim (как для WAIT-таймаутов в P1-5). Пересмотреть в P6-1.
- **CLAUDE.md канонические метрики устарели**: заявлено «119 тестов / JaCoCo 72%», фактически 361 тест / ~94%. Обновить документ (требует решения Дениса — не трогаю автономно).
- JaCoCo gate baseline: LINE ≥ 0.88, INSTR ≥ 0.90 (цель проекта — 85% по
  изменённому коду на гейте ревью, см. CLAUDE.md, п.5 рабочего протокола).
- `ApplicationModules.verify()`: зелёный, нарушений границ не найдено.
- Последний коммит фундамента: `42aabec` ("P0 guardrails: CI/CD, OpenAPI,
  structured logging + audit_log").

*Обновлено: 2026-06-19.*
