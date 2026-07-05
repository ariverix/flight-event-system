# ROADMAP — путь к промышленному внедрению

Цель: довести flight-event-system до **полноценной промышленной замены SITA AIRCOM Sequencer** — паритет по функциям + промышленная надёжность, безопасность, масштаб, эксплуатация и приёмка заказчиком.

Фазы идут по порядку. Внутри фазы `tech-lead` бьёт эпики на задачи, каждую делает профильный агент по TDD, `reviewer` — гейт. Каждая задача = вертикальный срез. Гоняем по одной (см. `RUN_PLAN.md`).

---

## P0 — Фундамент и гард-рейлы (без новых фич)
**Цель:** инфраструктура качества, чтобы дальше строить безопасно.
- CI/CD каркас (GitHub Actions): build+test+coverage gate ≥85%+Modulith-verify. — `devops-agent`
- Тест границ модулей `ApplicationModules.verify()` в CI. — `architect`
- OpenAPI (springdoc) + Swagger UI на текущем API. — `docs-agent`
- Каркас аудита и структурных логов, correlationId. — `observability-agent`
- Процесс ADR (`docs/adr/`), первый ADR: модульный монолит vs микросервисы. — `architect`
- Threat model + чек-лист ФСТЭК (черновик), license report в CI. — `security-agent`+`compliance-agent`
- `docs/PROGRESS.md` ledger. — `tech-lead`
**Приёмка:** зелёный пайплайн, гейты включены, базовые доки есть.

## P1 — Промышленное ядро движка
**Цель:** движок ECA уровня прод, паритет семантики + надёжность.
- Полный паритет 3 типов шагов и 6 типов критериев с AND/OR. — `sequence-engine-dev`
- Решения CONTINUE/GOTO/END/ABORT + Notify; start/stop с непрерывной оценкой. — `sequence-engine-dev`
- Персистентный стейт инстанса (`sequence_instance`) + resume после рестарта. — `sequence-engine-dev`+`db-dev`
- Durable-планировщик WAIT/таймаутов (БД-backed). — `sequence-engine-dev`
- Конкурентность: тысячи инстансов, оптимистические блокировки, без гонок. — `sequence-engine-dev`
- Transactional Outbox (`event_publication`), идемпотентный приём событий. — `architect`+`sequence-engine-dev`
- Event Log класса Tracking на завершение шагов. — `observability-agent`
**Приёмка:** сценарные тесты всех типов шагов/критериев зелёные; тест resume-после-рестарта зелёный.

## P2 — Интеграция ACARS и обмен сообщениями
**Цель:** реальный промышленный обмен «борт-земля».
- Входящий шлюз ACARS → событие `MessageReceived`, идемпотентность. — `integration-dev`
- Исходящий шлюз uplink/ground по команде движка. — `integration-dev`
- Парсеры ARINC 620/618 / Type B / AFTN. — `integration-dev`
- Разбор позывных + таблица соответствия (`callsign_matching`). — `integration-dev`+`db-dev`
- Источники позиций ACARS/ADS-B/radar; оценочные игнорируются. — `integration-dev`
- DLQ + reprocess, ретраи/backoff, circuit breaker. — `integration-dev`
**Приёмка:** e2e «сообщение → событие → реакция движка → исходящее»; сценарий DLQ; нагрузочный замер throughput.

## P3 — Шаблоны, custom fields, условия/алерты, уведомления
- Движок шаблонов (uplink/downlink/ground, computer-generated|external-user). — `templates-dev`
- Движок custom fields (извлечение из входящих, переиспользование). — `templates-dev`
- Условия/алерты + уровни + авто-закрытие при завершении рейса. — `alerts-dev`
- Event Handling (folder/sequence) + Notify-каналы (email/webhook). — `alerts-dev`
**Приёмка:** тесты рендеринга/извлечения; авто-закрытие условий; доставка уведомлений идемпотентна.

## P4 — Безопасность и эксплуатация прав
- RBAC user-rights (Manage sequences, edit Aircraft, …). — `security-agent`
- JWT+refresh, ротация/инвалидизация, BCrypt; ADR по крипто/токенам. — `security-agent`
- Секреты вне кода; путь к ГОСТ TLS. — `security-agent`+`devops-agent`
- OWASP dependency-check + SAST в CI (фейл на High/Critical). — `security-agent`
- Полный audit_log действий пользователя. — `security-agent`+`db-dev`
**Приёмка:** тесты доступа можно/нельзя; сканы зелёные; аудит пишется.

## P5 — Наблюдаемость и надёжность
- Метрики Prometheus (бизнес+техн) + дашборды Grafana + алерт-правила. — `observability-agent`
- Трассировка OpenTelemetry сквозная. — `observability-agent`
- Health liveness/readiness/startup, учитывающие БД/каналы. — `observability-agent`
- Бэкап/восстановление БД (PITR), тест восстановления. — `db-dev`+`devops-agent`
- Chaos/failover тесты (падение канала, отказ реплики). — `test-engineer`
**Приёмка:** дашборды/SLO как код; тест восстановления и failover зелёные.

## P6 — Масштаб и производительность
- HA: несколько реплик backend; leader election планировщика (single-fire в кластере). — `devops-agent`
- Горизонтальное масштабирование, requests/limits, автоскейл. — `devops-agent`
- Партиционирование/retention больших таблиц (event log, messages, audit). — `db-dev`
- Нагрузочное тестирование до целевых чисел, профилирование, тюнинг. — `test-engineer`+`perf` (через `bug-fixer`/`sequence-engine-dev`)
**Приёмка:** подтверждённый single-fire в 2+ репликах; нагрузочный отчёт с p95/p99 и целевым throughput.

## P7 — Промышленный UX фронтенда ✅ DONE
- Каркас FE: структура, стор, API-клиент из OpenAPI, WebSocket-слой. — `frontend-architect`
- Редактор React Flow: ноды шагов, рёбра-решения, drag-n-drop с авто-GOTO, панель start/stop. — `ui-agent`
- Формы всех типов шагов/критериев с валидацией (нельзя собрать невалидное). — `ui-agent`
- Реал-тайм статусы инстансов + Event Log + таймлайн истории. — `ui-agent`
- i18n RU/EN, роль-зависимый UI, a11y, скелетоны/анимации. — `frontend-architect`+`ui-agent`
**Приёмка:** редактор собирает все сценарии паритета; реал-тайм работает; i18n полный.

## P8 — Упаковка, деплой, документация, соответствие ✅ DONE
- Kubernetes/Helm чарты, blue-green/rolling, миграции при деплое. — `devops-agent`
- Руководство администратора (RU) по образцу SITA ASP + ранбуки эксплуатации. — `docs-agent`
- Матрица паритета SITA→наше→тест (UAT), license report, материалы для Реестра российского ПО. — `compliance-agent`
- Приёмочный прогон по матрице паритета. — `tech-lead`+`compliance-agent`
**Приёмка:** деплой в k8s проходит; матрица паритета закрыта; пакет документов для заказчика готов.

---
**Глобальный Definition of Done фазы:** все эпики закрыты по DoD задач, пайплайн зелёный, доки/матрица обновлены, нет открытых High/Critical по безопасности.

---

## Итог P7–P8

Фазы P7 и P8 завершены 2026-06-28. Ниже — ключевые результаты.

- **Промышленный UI (P7):** Реализован полный React 18 + TypeScript + Ant Design 5 фронтенд. Редактор последовательностей на React Flow с кастом-нодами 3 типов шагов, рёбрами-решениями (true/false, CONTINUE/GOTO/END/ABORT + Notify), drag-n-drop с авто-пересчётом GOTO-ссылок. Формы всех 3 типов шагов и 6 типов критериев с рекурсивным конструктором AND/OR и валидацией без хардкода строк. Реал-тайм дашборд через WebSocket (`/ws/eca`) с JWT-аутентификацией, трансляцией событий выполнения и Event Log. Полный i18n RU/EN, роль-зависимый UI, a11y-фиксы. Тестовая база 183+ тестов (vitest).

- **Helm/Kubernetes (P8-1):** Helm-чарт `deploy/helm/eca-system` с шаблонами для backend (Deployment, Service, HPA autoscaling/v2), frontend (nginx multi-stage), PostgreSQL (StatefulSet + PVC), Ingress, ConfigMap, Secret. Профили values для staging/prod. Манифесты `deploy/k8s/` для прямого k8s-деплоя (backend-deployment.yaml, backend-hpa.yaml). Backend stateless + leader election = горизонтальное масштабирование без координации.

- **Документация (P8-2):** Руководство администратора `docs/admin/` (installation, configuration, operations) на русском языке по образцу SITA ASP. Ранбуки эксплуатации `docs/admin/runbooks/` — 5 сценариев: рестарт сервиса, резервное копирование/восстановление БД, инцидент высокой нагрузки CPU, инцидент исчерпания соединений БД, зависший лидер кластера.

- **Соответствие (P8-3):** Матрица паритета SITA→ECA (87/90 = 97%), license report зависимостей (Maven), материалы для Реестра российского ПО (`docs/compliance/russian-software-registry.md`), UAT-чеклист для заказчика (`docs/compliance/uat-checklist.md`).

- **Исправление матрицы (P8-4):** При финальном прогоне выявлена ошибка compliance-agent: пункт 15.3 (HPA) помечен ЧАСТИЧНО, хотя Helm-чарт с HPA реализован в P8-1. Статус исправлен на РЕАЛИЗОВАНО. Итог матрицы: 87 РЕАЛИЗОВАНО (97%), 3 ЧАСТИЧНО (6.3, 12.2, 12.3 — согласованы с заказчиком как приемлемые риски).

- **Система готова к UAT.** Функциональный паритет с SITA AIRCOM Sequencer подтверждён по всем категориям. Оставшиеся 3 ЧАСТИЧНО — архитектурные решения (per-sequence фильтр типов ВС) и зона внешней инфраструктуры (radar/ADS-B feed-адаптеры).

- **Пакет документов для заказчика:** `docs/compliance/parity-matrix.md`, `docs/compliance/uat-checklist.md`, `docs/compliance/license-report.md`, `docs/compliance/russian-software-registry.md`, `docs/admin/`, `docs/security/gost-tls-path.md`.

---

## Итог прогона «Промышленный апгрейд» (2026-07-03 … 2026-07-04)

После закрытия P0–P8 выполнен сквозной прогон промышленного укрепления (план — `CLAUDE_CODE_PROMPT.md`, ledger — `docs/PROGRESS.md`, блокеры — `BLOCKERS.md`). Фазы 0–8, коммиты `bf07d31`…HEAD:

- **Целостность движка под HA (Ф1–Ф2):** dedup startExecution перенесён на UNIQUE-индекс БД (V38, ADR-0007) — exactly-once старт при любом числе реплик; @Scheduled-поллеры гейтятся готовностью приложения (`ApplicationReadiness`, модуль cluster); найдена и структурно устранена флейки-гонка поллеров с межтестовым Flyway-clean (`SchedulingConfig`, тумблер `app.scheduling.enabled`).
- **Безопасность (Ф3):** rate limiting bucket4j (ADR-0006) — анти-брутфорс `/auth` + потолок ингеста, анти-спуфинг XFF, LRU-бакеты; санитизация логов внешней атак-поверхности (`LogSanitizer`); CORS вынесен в env; R-4 threat model → Mitigated.
- **Наблюдаемость (Ф4):** метрики/алерты/панели новых путей (429 по scope, отклонённые дубли старта) + scrape-ассерты в тестах.
- **API/БД (Ф5):** `GET /api/v1/aircraft` (реестр бортов — проекция из messages; закрыт TODO P7-3 aircraft-bindings); ревью индексов — горячие пути покрыты, V39 не потребовался; OpenAPI + фронт-клиент пересинхронизированы.
- **Frontend (Ф6):** `AircraftPicker` (серверный поиск, debounce, race-guard) + фильтр журнала по реальным бортам; Playwright smoke E2E — 6 сценариев против реального стека; 208 vitest-тестов.
- **CI/CD (Ф7):** frontend-job стал гейтом (lint+vitest), новые jobs e2e (полный стек в CI) и docker (build образов + helm lint ×3, build-only до появления секрета registry); helm lint сразу нашёл дефект чарта P8-1 (чарт не парсился — шаблонные выражения в YAML-комментариях) — исправлен.
- **Финал (Ф8):** quality gate по diff всего прогона — PASS (0 CRITICAL/HIGH); ADR-0006/0007; синхронизация доков (README quickstart 8081, installation.md — реальные пути `/api/v1` и процедура замены демо-админа, UAT-чеклист); license report дополнен; `PRODUCTION_READINESS_REPORT.md`.

Метрики после прогона: миграции V1–V38; ~906 backend-тестов; 208 frontend (vitest) + 6 E2E (Playwright); JaCoCo LINE≥0.88/INSTR≥0.90 держится. Открытые блокеры — только внешние (секреты registry/NVD, branch protection, приёмочный стенд) — см. `BLOCKERS.md`.
