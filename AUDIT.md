# AUDIT — production-readiness (фаза 0 прогона, 2026-07-03)

> Полный аудит по 12-пунктному чек-листу. Статусы: **СДЕЛАНО** / **ЧАСТИЧНО** / **ПРОБЕЛ** / N/A.
> Каждый пункт — со ссылками на файлы-доказательства. Пробелы разнесены по фазам в `IMPROVEMENT_PLAN.md`.

## 1. Тестирование — ЧАСТИЧНО
- **СДЕЛАНО** backend: 103 тест-класса (`backend/src/test/java/...`), unit + integration (`BaseIntegrationTest` → реальный Postgres) + architecture (`ModularityTests.java`). ~843 теста (сводка `docs/PROGRESS.md`).
- **СДЕЛАНО** JaCoCo-гейт: `backend/pom.xml` (BUNDLE LINE ≥ 0.88, INSTRUCTION ≥ 0.90) в фазе `verify`.
- **ЧАСТИЧНО** frontend: 8 vitest-файлов / 183+ тестов (`frontend/src/**/__tests__/`), но в CI не гоняются (см. п.4).
- **ПРОБЕЛ** E2E: Playwright отсутствует полностью (нет `frontend/e2e`, нет конфига, нет зависимости). → Фазы 6–7.

## 2. Наблюдаемость — СДЕЛАНО
- JSON-логи: `backend/src/main/resources/logback-spring.xml` (StructuredLogEncoder, ECS; dev — текст).
- correlationId: `CorrelationContext.java`, `CorrelationIdFilter.java` (MDC), пишется в audit_log (P4-5).
- Micrometer/Prometheus: `micrometer-registry-prometheus`, `/actuator/prometheus` за SYSTEM_ADMIN; бизнес-метрики eca.* (execution/messages/dlq/conditions/notifications/retention).
- OTel: `micrometer-tracing-bridge-otel` + OTLP-экспорт (по умолчанию off), доменные span'ы.
- Health-пробы liveness/readiness/startup: `application.yml` + точечный permitAll в `SecurityConfig`.
- Как код: `observability/grafana/eca-system-dashboard.json`, `observability/prometheus/alerts.yml`, `observability/slo.yml`.
- Gap (по CLAUDE.md п.5): новые пути этого прогона (rate limiter, dedup-claim) обязаны получить метрики/панели. → Фаза 4.

## 3. Безопасность — ЧАСТИЧНО
- **СДЕЛАНО**: default-deny `/api/**` (`user/SecurityConfig.java:79-81`), stateless JWT + refresh с ротацией (`JwtService`, `RefreshTokenService`, V35, ADR-0003), гранулярный RBAC `hasAuthority(...)` + 32 `@PreAuthorize`, секреты в env (`.env.example`, `docs/security/secrets-management.md`), Problem Details на отказах, аудит-лог полный (P4-5). ACARS-ингест `/api/v1/messages/**` без JWT — осознанное решение (сетевое ограждение), НЕ дефект.
- **ПРОБЕЛ** rate limiting: bucket4j/resilience4j в `backend/pom.xml` нет; `/api/v1/auth/**` (брутфорс) и ACARS-ингест (флуд) не ограничены. → Фаза 3.
- **ПРОБЕЛ** санитизация логов: утилиты нет; 6× CRLF_INJECTION_LOGS + 1× INFORMATION_EXPOSURE (SpotBugs Medium, backlog P4-4). → Фаза 3.
- **Замечание**: CORS-origins захардкожены dev-значениями (`localhost:5173/3000`, `SecurityConfig.corsConfigurationSource`) — для прода вынести в конфиг. → Фаза 3 (попутно).

## 4. CI/CD — ЧАСТИЧНО
`.github/workflows/ci.yml`, 3 джоба:
- **СДЕЛАНО** backend: mvn verify (тесты + JaCoCo-гейт + Modulith + license report) с Postgres-сервисом, артефакты.
- **СДЕЛАНО** security-scan: OWASP Dependency-Check (fail CVSS≥7), SpotBugs/FindSecBugs, Semgrep.
- **ПРОБЕЛ** frontend: `continue-on-error: true` (строка 179) — job не гейтит; vitest и lint в CI не запускаются вовсе. → Фаза 7.
- **ПРОБЕЛ**: нет e2e-job, нет docker-build-job, нет `helm lint`. → Фаза 7.

## 5. БД — СДЕЛАНО
- 37 миграций Flyway (V1–V37), 23 доменные таблицы.
- Партиционирование: V37 — `tracking_event_log` RANGE по created_at помесячно, DEFAULT-партиция.
- Retention: `RetentionService` (drop партиций TEL 6 мес, delete messages 90 дн / audit_log 365 дн, create-ahead, cron 03:00, только лидер).
- Индексы: 21 миграция с CREATE INDEX; dedup-индекс V23 НЕ уникальный (см. п.6-риск).
- Gap: EXPLAIN ANALYZE горячих запросов после V37 не проводился. → Фаза 5.

## 6. Архитектура — СДЕЛАНО (1 живой риск)
- Модульный монолит, 10 модулей (`sequence`, `execution`, `eventprocessor`, `integration`, `user`, `templates`, `customfields`, `conditions`, `eventhandling`, `cluster`), гексагональная структура adapter/application/domain/port; Spring Modulith 1.3.1; `ModularityTests` (ApplicationModules.verify) зелёный в CI.
- ADR-0001…0005 в `docs/adr/` + шаблон.
- Outbox `event_publication` (ADR-0002), leader election на PostgreSQL (ADR-0004, V36).
- **РИСК (живой backlog)**: dedup `startExecution` — read-then-write без unique constraint (`ExecutionService.startExecution` → `existsByDedupKey` → `save`; индекс V23 обычный). k8s-деплой уже replicas:2 + HPA до 8 — двойной старт инстанса при конкурентной доставке реален. → Фаза 1 (V38).
- Гигиена: `@Scheduled`-поллеры без гейта готовности (backlog P2-3); мёртвый `CriterionEvaluator.getCustomFieldValue` (только тесты). → Фаза 1.

## 7. API — ЧАСТИЧНО
- **СДЕЛАНО**: 14 контроллеров (sequence/execution/messages/raw/dlq/templates/custom-field-rules/conditions/folders/event-handlers/users/auth/audit-log/spa), springdoc, Swagger UI за RBAC.
- **ЧАСТИЧНО**: `docs/openapi/openapi.json` устарел (последний коммит 2026-06-23, контроллеры правились позже). → Фаза 5 (перегенерация + `npm run gen:api`).
- **ПРОБЕЛ**: `/api/v1/aircraft` (список бортов для UI привязок, TODO P7-3) отсутствует. → Фаза 5.

## 8. Конфигурация — СДЕЛАНО
- `application.yml` (+`application-dev.yml`): все секреты/тюнинг через env с dev-фоллбэками (DB_*, JWT_*, LOG_*, OTLP_*, RETENTION_*, DB_POOL_MAX=20 после P6-3).
- `.env.example`; Helm-чарт `deploy/helm/eca-system` (values base/staging/prod, backend+frontend+postgres+ingress+secret+hpa).
- Замечание: настройки нового rate limiter'а — тоже по профилям через env. → Фаза 3.

## 9. Обработка ошибок — ЧАСТИЧНО
- **СДЕЛАНО** backend: `GlobalExceptionHandler` — RFC 7807 ProblemDetail (404/400/409/403/500, validation), AccessDenied → 403.
- **ПРОБЕЛ** frontend: ErrorBoundary отсутствует (grep по `frontend/src` пуст; lazy-роуты в `App.tsx` есть, но без boundary — вводная прогона здесь неточна). → Фаза 6 (небольшой компонент + тест).

## 10. Frontend — ЧАСТИЧНО
- **СДЕЛАНО**: слои `api/` (генерированный OpenAPI-клиент `api/generated/`, WS-слой `api/ws/`), 4 Zustand-стора, i18n RU/EN (`i18n/dict.ts`), React Flow редактор (P7-2/P7-3), реал-тайм дашборд инстансов по WS (P7-4), a11y/скелетоны (P7-5).
- **ПРОБЕЛ**: aircraft-bindings UI (TODO P7-3). → Фаза 6.
- **ПРОБЕЛ**: ErrorBoundary (см. п.9), E2E (см. п.1).

## 11. Документация — СДЕЛАНО
- Корень: README.md, ROADMAP.md, DEMO_SCRIPT.md, TEAM.md, RUN_PLAN.md, docs/PROGRESS.md.
- `docs/admin/` (RU: installation/configuration/operations + 5 ранбуков), `docs/runbooks/chaos-failover.md`, `docs/compliance/` (матрица паритета 87/90, license report, реестр, UAT), `docs/security/` (threat model, ФСТЭК, ГОСТ TLS, секреты), `docs/perf/` (P6-3), `docs/adr/` (5), `observability/README.md`.
- Gap: обновление после этого прогона (ADR rate-limiting/dedup, README quickstart порт 8081, PROGRESS). → Фаза 8.

## 12. Docker/deploy — СДЕЛАНО
- Dockerfile'ы multi-stage + non-root: корневой (frontend→backend→JRE, `USER app`), `backend/Dockerfile`, `frontend/Dockerfile` (nginx, `USER nginx`).
- `docker-compose.yml` (postgres без публикации порта, app 8081→8080), `deploy/k8s/` (deployment replicas:2 + HPA), Helm-чарт полный.
- Gap: docker build и helm lint не в CI. → Фаза 7.

---

## Сводка пробелов → фазы

| # | Пробел | Фаза |
|---|---|---|
| 1 | Dedup startExecution без unique constraint (HA-риск) | 1 |
| 2 | Гейтинг @Scheduled по готовности; мёртвый getCustomFieldValue | 1 |
| 3 | Rate limiting auth + ACARS-ингест; санитизация логов (7 SpotBugs Medium); CORS-origins в конфиг | 3 |
| 4 | Метрики/панели новых путей | 4 |
| 5 | /api/v1/aircraft; OpenAPI устарел; EXPLAIN ANALYZE после V37 | 5 |
| 6 | Aircraft-bindings UI; ErrorBoundary; Playwright E2E | 6 |
| 7 | CI: frontend-гейт (vitest+lint), e2e-job, docker+helm lint job | 7 |
| 8 | Доки/ADR/отчёт готовности | 8 |

*Составлено в фазе 0 прогона «промышленный апгрейд», 2026-07-03. Доказательства собраны сканом репозитория (без `.claude/worktrees/`, `target/`).*
