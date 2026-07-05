# PRODUCTION READINESS REPORT — ECA System (flight-event-system)

**Дата:** 2026-07-04
**Контекст:** итог прогона «Промышленный апгрейд» (2026-07-03 … 2026-07-04, фазы 0–8)
поверх завершённых P0–P8. Исходный аудит — `AUDIT.md` (фаза 0), ledger — `docs/PROGRESS.md`,
план — `CLAUDE_CODE_PROMPT.md`, блокеры — `BLOCKERS.md`.
**Вердикт:** система готова к UAT и промышленному пилоту; открытые пункты — только внешние
(секреты/настройки репозитория/стенд заказчика), кодовых блокеров нет.

## Чек-лист готовности (12 пунктов)

| # | Область | Статус | Доказательство | Остаточные риски |
|---|---------|--------|----------------|------------------|
| 1 | Тестирование | ✅ ГОТОВО | ~906 backend-тестов (`mvn verify`: unit + integration на реальном PostgreSQL + architecture/Modulith + JaCoCo gate LINE≥0.88/INSTR≥0.90); 208 frontend (vitest); 6 E2E Playwright против реального стека (`frontend/e2e/`); конкурентные тесты HA-путей (dedup 8 потоков, лидер-элекшн, оптимистические блокировки); хаос-тесты (`P5_4_*`) | Флейки-гонка поллеров с Flyway-clean устранена структурно (Фаза 2, `SchedulingConfig`); разовый env-флейк E2E задокументирован (PROGRESS Ф6) |
| 2 | Наблюдаемость | ✅ ГОТОВО | Micrometer→Prometheus (`/actuator/prometheus` за RBAC), бизнес-метрики всех критических путей вкл. новые `eca.ratelimit.rejected{scope}`/`eca.execution.start.duplicate_rejected` (scrape-ассерты в `P5_1`); OpenTelemetry-трейсинг с доменными span'ами; структурные JSON-логи + correlationId; Grafana-дашборд + alert-правила + SLO как код (`observability/`); k8s-пробы liveness/readiness/startup (`P5_3`, show-details: never) | Экспорт OTLP по умолчанию выключен (включается env — осознанно) |
| 3 | Безопасность | ✅ ГОТОВО | RBAC user-rights (гранулярные Permission, default-deny `/api/**`); JWT 15 мин + refresh-ротация с reuse-detection (V35, ADR-0003); rate limiting bucket4j c анти-спуфингом XFF и LRU (Фаза 3, ADR-0006); санитизация логов атак-поверхности (`LogSanitizer`); секреты в env (`.env.example`, `docs/security/secrets-management.md`); OWASP DC (CVSS≥7 фейл) + SpotBugs/FindSecBugs (High) + Semgrep в CI; полный audit_log с correlationId; путь к ГОСТ TLS (`docs/security/gost-tls-path.md`); threat model R-1..R-9 актуализирована (R-4 Mitigated) | ACARS-ингест открыт осознанно (сетевое ограждение mTLS/allowlist — CLAUDE.md §7); эндпоинта смены пароля нет — процедура замены демо-админа задокументирована (`installation.md`), API — backlog; остаточные SpotBugs Medium — BLOCKERS.md |
| 4 | CI/CD | ✅ ГОТОВО (код) | 5 jobs: `backend` (verify+JaCoCo+Modulith+license), `security-scan` (OWASP DC+SpotBugs+Semgrep, фейл на High/Critical), `frontend` (typecheck+lint+vitest+build — ГЕЙТ с Фазы 7), `e2e` (полный стек+Playwright), `docker` (build образов+helm lint ×3). Каждый шаг воспроизведён локально (репо приватное) | ⚠️ Required status checks — настройка GitHub Branch Protection, задаёт владелец репо (BLOCKERS.md); push образов — нет секрета registry (build-only, BLOCKERS.md); NVD_API_KEY локально не задан (в CI есть) |
| 5 | БД | ✅ ГОТОВО | 23 таблицы, миграции V1–V38 (только через Flyway); партиционирование `tracking_event_log` по месяцам + retention-by-deletion (V37, лидер-гейт); индексы горячих путей проверены EXPLAIN (Фаза 5, V39 не потребовался); бэкап/восстановление (`ops/backup/` + инт-тест дампа/рестора); dedup-инвариант на UNIQUE-индексе (V38, ADR-0007) | Материализованный last-seen / pg_trgm для aircraft-поиска — только при экстремальном масштабе (зафиксировано, Фаза 5) |
| 6 | Архитектура | ✅ ГОТОВО | Модульный монолит Spring Modulith, 10 модулей, гексагональная; `ApplicationModules.verify()` зелёный в CI; HA: лидер-элекшн на PostgreSQL (ADR-0004) + optimistic locking + DB-claim single-fire + dedup V38 — корректность подтверждена сквозным quality gate Фазы 8 (PASS, 0 CRITICAL/HIGH); 7 ADR (`docs/adr/`, индекс актуален) | Дублирование ready-флага в `LeaderElectionService` — осознанное (MEDIUM-заметка гейта, информационная) |
| 7 | API | ✅ ГОТОВО | Все эндпоинты под `/api/v1`, OpenAPI (`docs/openapi/openapi.json`) синхронизирован с live `/v3/api-docs` (Фаза 5), фронт-клиент генерируется из него (`npm run gen:api`); `GET /api/v1/aircraft` закрыл последний TODO (aircraft-bindings); 429 задокументирован `@ApiResponse`; единый `@ControllerAdvice`, RFC 7807 для 429 | — |
| 8 | Конфигурация | ✅ ГОТОВО | Всё окружение-зависимое в env с dev-фоллбэками: БД, JWT, пул (DB_POOL_MAX), rate limiting (`app.ratelimit.*`), CORS (`app.cors.allowed-origins`), retention, tracing; `docker-compose` порт хоста 8081; Helm values по профилям staging/prod; `docs/admin/configuration.md` | Прод обязан переопределить dev-фоллбэки (помечено в application.yml и secrets-management.md) |
| 9 | Обработка ошибок | ✅ ГОТОВО | Единый `@ControllerAdvice` (вкл. AccessDenied→403); DLQ + reprocess/discard + exp-backoff + durable circuit breaker (V30); идемпотентность приёма (TOCTOU-safe) и потребителя (Outbox + V38); транзакционные отравления устранены точечными `noRollbackFor`; graceful-обработка проигрыша dedup-гонки как no-op | — |
| 10 | Frontend | ✅ ГОТОВО | React 18 + TS5 strict (0 `any`), Zustand, клиент из OpenAPI, i18n RU/EN без хардкода, a11y; редактор React Flow (3 типа шагов, 6 критериев, решения true/false); реал-тайм WS-дашборд; AircraftPicker + фильтр журнала (Фаза 6); 208 vitest + 6 E2E; lint 0 errors; build OK | 66 ESLint-warnings — стабильный baseline (не растёт, гейт по errors); `MessageLog` без компонентного теста (покрыт E2E) — LOW backlog |
| 11 | Документация | ✅ ГОТОВО | Руководство администратора RU (`docs/admin/` — installation/configuration/operations, синхронизировано Фазой 8: реальные пути `/api/v1`, порт 8081, процедура замены демо-админа) + 5 ранбуков; UAT-чеклист; матрица паритета 87/90; материалы Реестра; license report v1.1 (+bucket4j, +playwright); 7 ADR; README quickstart; DEMO_SCRIPT актуализирован; PROGRESS-ledger полный | — |
| 12 | Docker/deploy | ✅ ГОТОВО | Multi-stage образы backend/frontend, non-root; docker-compose (8081); Helm-чарт (backend+HPA min2/max8, frontend nginx, PostgreSQL StatefulSet, Ingress, Secret с resource-policy keep) — **чарт теперь реально линтуется** (дефект парсинга исправлен Фазой 7, 3 профиля зелёные); k8s-манифесты прямого деплоя; graceful shutdown; `helm lint` в CI | Push в registry — ждёт секрета (BLOCKERS.md); деплой в реальный кластер — приёмка на стенде заказчика |

## Итог BLOCKERS.md (все — внешние, кодовых нет)

| Блокер | Тип | Разблокировка |
|---|---|---|
| NVD_API_KEY локально | Секрет | Задать env на дев-машине (в CI задан) |
| Push docker-образов | Секрет | Секрет registry (URL+credentials) в CI |
| Приёмочный перф-прогон | Стенд | Стенд заказчика (индикативно: устойчиво ~220 msg/s, потолок ~375/s, 0% потерь — `docs/perf/`) |
| Branch protection (required checks) | Настройка репо | Денис: Settings → Branches → main → required: `backend`, `frontend`, `e2e` |
| Ветка `claude/strange-jang-f38717` | Решение владельца | Осмотреть/удалить или заархивировать тегом (18 уникальных коммитов раннего прототипа) |
| Scope-решения (CRLF Medium, SQL_INJECTION false-positive, DLQ stacktrace) | Зафиксированы | Действий не требуют; по желанию заказчика — сплошная санитизация |

## Ключевые числа

- Backend: ~906 тестов, JaCoCo LINE≥0.88 / INSTR≥0.90 (гейт в `mvn verify`).
- Frontend: 208 vitest + 6 Playwright E2E; tsc 0; ESLint 0 errors.
- Миграции: V1–V38. Матрица паритета SITA: 87/90 (97%), 3 ЧАСТИЧНО согласованы.
- Quality gate прогона (сквозной diff 9 коммитов): PASS, 0 CRITICAL/HIGH.
- Перф (индикативно, дев-машина): устойчиво ~220 msg/s (p95≤26мс), потолок ~375/s, 0% ошибок на всех уровнях.

*Финальный verification loop (mvn verify + security-профиль + FE loop + E2E) — см. запись Фазы 8 в `docs/PROGRESS.md` (выполняется последним шагом прогона; при любом красном отчёт не публикуется как финальный).*
