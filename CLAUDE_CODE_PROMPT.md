# Промышленный апгрейд ACARS ECA System — план работы для Claude Code

Проект: `C:\Projects\flight-event-system` (репо `ariverix/flight-event-system`, ветка `main`, репо приватное — `gh` и токена НЕТ, статус CI проверяется только локальной репродукцией гейтов).

## Контекст: что уже сделано — НЕ переделывать

Фазы P0–P8 из `ROADMAP.md` закрыты 2026-06-28. Уже есть и работает:
- Backend ~843 теста, JaCoCo-гейт в `mvn verify`: LINE ≥ 0.88, INSTRUCTION ≥ 0.90. Гейт НЕ понижать.
- `ApplicationModules.verify()` зелёный, цикла `sequence↔user` нет, outbox `event_publication` (ADR-0002).
- RBAC + default-deny `/api/**`, JWT+refresh (V35), Problem Details RFC 7807 в `GlobalExceptionHandler`, CORS.
- CI `.github/workflows/ci.yml`: backend-гейт, security-scan (OWASP Dependency-Check fail CVSS≥7, SpotBugs/FindSecBugs, Semgrep), frontend (typecheck+build).
- OpenAPI `docs/openapi/openapi.json` (+ `npm run gen:api` во фронтенде), 5 ADR в `docs/adr/`, админ-доки RU и ранбуки в `docs/admin/`, матрица паритета SITA 97% в `docs/compliance/`.
- Observability: JSON-логи + correlationId, Micrometer/Prometheus, OTel, health-пробы, Grafana/SLO в `observability/`, k6 в `perf/`, бэкапы в `ops/backup`.
- Helm-чарт `deploy/helm/eca-system`, k8s-манифесты, leader election (V36), партиционирование tracking_event_log (V37), HPA.
- Frontend: React Flow редактор, 183+ vitest-тестов, ErrorBoundary + lazy-роуты в `App.tsx`, i18n RU/EN, Zustand + OpenAPI-клиент (React Query НЕ вводить — стор выбран, менять запрещено CLAUDE.md).

Задача этого прогона — закрыть ЗАФИКСИРОВАННЫЙ технический долг (см. «Backlog / follow-up» в `docs/PROGRESS.md`) и оставшиеся пробелы, а не строить заново.

## Правила прогона (обязательны)

1. Перед КАЖДОЙ фазой: перечитай `CLAUDE.md` и `.claude/rules/common/` (минимум `coding-style.md`, `testing.md`, `security.md`, `git-workflow.md`). Рабочий протокол CLAUDE.md действует целиком; п.8 («одна задача — STOP») на этот прогон снят Денисом: работай автономно по всем фазам подряд, без вопросов «продолжить?».
2. TDD и вертикальный срез — как в CLAUDE.md. Любое изменение схемы — только новая Flyway-миграция `V38+` (пишет `db-dev`), применённые V1–V37 не трогать.
3. Внутри каждой фазы веди прогресс через TodoWrite. Каждый шаг завершается verification loop (скилл `verification-loop`): backend `bash mvnw -B verify` (env: JAVA_HOME=~/.jdks/ms-21.0.11, Postgres-контейнер `eca-pg` должен быть запущен, `mvn verify` гонять только серийно, не параллелить); frontend `npm run check && npm run lint && npm run test:run && npm run build`.
4. Гейт закрытия фазы: тесты зелёные → JaCoCo-гейт держится → агент `reviewer` дал PASS → доки/OpenAPI обновлены. Затем `/checkpoint`, коммит по conventional commits (`fix(security): ...`, `feat(ci): ...`, `test(e2e): ...`) и краткий отчёт в `docs/PROGRESS.md`. НЕ пушить — только локальные коммиты; Денис пушит сам.
5. Заблокирован (нужен секрет, внешняя инфраструктура, решение заказчика) — пиши блокер в `BLOCKERS.md` (что, почему, что нужно для разблокировки) и переходи к следующей задаче фазы. Работу не останавливай.
6. Красные тесты/конфликт миграций — сначала локальный откат (`git checkout -- <files>` / удаление НЕприменённой миграции), затем `bug-fixer` или `java-build-resolver`/`react-build-resolver`. Сломанное не коммитить.
7. Безопасность: секретов в коде нет; эндпоинт приёма ACARS (`/api/v1/messages/**`) осознанно без JWT (сетевое ограждение) — не «чинить» его аутентификацией.

## Фаза 0 — Аудит, синхронизация фактов, план

Агенты: `tech-lead`, `architect`, `code-explorer`. Команды/скиллы: `/plan`, `repo-scan`, `verification-loop`.
1. Разбери 3 незакоммиченных файла (`.gitignore`, `docker-compose.yml`, `frontend/src/store/__tests__/instancesStore.test.ts`): пойми diff, прогони verification loop; осмысленные изменения закоммить `chore: ...`, мусор откати.
2. Полный аудит по чек-листу (тесты, наблюдаемость, безопасность, CI/CD, БД, архитектура, API, конфигурация, ошибки, frontend, доки, Docker/deploy) → `AUDIT.md`: статус каждого пункта (СДЕЛАНО / ПРОБЕЛ / N/A) со ссылками на файлы-доказательства.
3. Сверь «Backlog / follow-up» из `docs/PROGRESS.md` с кодом — что уже закрыто, что живо → `IMPROVEMENT_PLAN.md` с приоритетами P1/P2/P3 и привязкой к фазам 1–8.
4. Обнови канонические метрики в `CLAUDE.md` (сейчас «119 тестов / 72%» — фактически 843+ / ~94%, миграции V1–V37): правка санкционирована этим прогоном.
DoD: `AUDIT.md`, `IMPROVEMENT_PLAN.md` созданы; рабочее дерево чистое; `CLAUDE.md` актуален; `reviewer` PASS. Коммит `docs(audit): ...` + `/checkpoint`.

## Фаза 1 — Целостность движка под HA (закрытие архитектурного backlog)

Агенты: `architect`, `sequence-engine-dev`, `db-dev`, `backend-dev`, `reviewer`. Скиллы: `springboot-patterns`, `hexagonal-architecture`, `jpa-patterns`, `database-migrations`.
1. **Dedup `startExecution`** (backlog P1-7/P6-1): сейчас read-then-write без unique constraint — при 2+ репликах backend возможен двойной старт инстанса. TDD: сначала тест на конкурентный двойной старт → миграция `V38` (unique constraint или claim-механизм, как у WAIT-таймаутов P1-5) → реализация.
2. **Гейтинг `@Scheduled`-поллеров по `ApplicationReadyEvent`** (backlog P2-3): `OutboundMessageDeliveryScheduler` и прочие не должны тикать до готовности схемы.
3. **Мёртвый код** (backlog P3-2): проверь, вызывается ли `CriterionEvaluator.getCustomFieldValue` production-путём после P3-3; если нет — удалить с тестами (`refactor-cleaner`).
4. Прогони `ApplicationModules.verify()` и убедись, что границы модулей целы.
DoD: конкурентный тест двойного старта зелёный; `mvn verify` зелёный, JaCoCo-гейт держится; `reviewer` PASS. Коммит `fix(engine): ...` + `/checkpoint`.

## Фаза 2 — Стабилизация и добивка тестов backend

Агенты: `qa-agent`, `tdd-guide`, `test-engineer`, `bug-fixer`. Команды/скиллы: `/test-coverage`, `springboot-tdd`, `tdd-workflow`, `springboot-verification`.
1. Полный `bash mvnw -B verify`: если что-то красное (включая исторически проблемные `EcaRuleEngineTest`, `UserServiceTest`, `CriterionEvaluatorTest` — по PROGRESS.md давно зелёные, но проверь) — `bug-fixer` чинит реализацию, не тесты.
2. `qa-agent`: JaCoCo-отчёт → классы ниже 85% line coverage в изменённых фазой 1 модулях → добить юнит-тестами edge-кейсов.
3. `test-engineer`: убедись, что интеграционные тесты (BaseIntegrationTest → Postgres `eca_test`) воспроизводимы локально и в CI.
DoD: `mvn verify` зелёный; гейт LINE ≥ 0.88 / INSTR ≥ 0.90 держится; флаки нет (два прогона подряд зелёные). Коммит `test(backend): ...` + `/checkpoint`.

## Фаза 3 — Безопасность: rate limiting + санитизация логов

Агенты: `security-agent`, `security-reviewer`, `compliance-agent`, `reviewer`. Команды/скиллы: `/security-scan`, `springboot-security`, `security-review`, `error-handling`.
1. **Rate limiting** (единственный незакрытый пункт чек-листа): bucket4j (лицензия Apache-2.0 — совместима с Реестром, `compliance-agent` подтверждает) на `/api/v1/auth/**` (брутфорс) и открытый ACARS-ингест `/api/v1/messages/**` (флуд). Настройки лимитов — через `application.yml` (по профилям), метрика отклонённых запросов через Micrometer, 429 через Problem Details. TDD.
2. **SpotBugs Medium из backlog P4-4**: 6× CRLF_INJECTION_LOGS (MessageController:129, DeadLetterQueueService:182/198, DeadLetterController:72/80, UserController:43/46, UserService:96/106, User:26) — единая утилита санитизации CR/LF логируемого пользовательского ввода; 1× INFORMATION_EXPOSURE (DeadLetterQueueService:232) — не отдавать внутренние детали в ответ.
3. Локальный прогон сканов (гейты CI воспроизводятся локально, см. PROGRESS.md): `bash mvnw -B -Psecurity-scan dependency-check:check` (нужен NVD_API_KEY — нет ключа → блокер в BLOCKERS.md, остальное не ждёт), `bash mvnw -B -Psecurity-scan -DskipTests compile spotbugs:check`, semgrep (`p/java p/owasp-top-ten p/secrets`, с учётом `.semgrepignore`).
DoD: rate limiting под тестами; 7 Medium-находок закрыты, SpotBugs security-профиль чистый; Semgrep без новых находок; `reviewer` PASS. Коммит `fix(security): ...` + `/checkpoint`.

## Фаза 4 — Наблюдаемость: только gap-fill

Агенты: `observability-agent`, `devops-agent`. Скилл: `dashboard-builder`.
Стек наблюдаемости уже есть (JSON-логи, MDC correlationId, Micrometer/Prometheus, OTel, health-пробы, Grafana/SLO как код). По CLAUDE.md п.5 — новые пути обязаны иметь метрики/логи:
1. Метрики и структурные логи для rate limiter'а (фаза 3) и claim-механизма dedup (фаза 1).
2. Обнови `observability/grafana` и `observability/slo.yml`: панель/алерт по 429 и по конфликтам дедупа.
3. Проверь, что `/actuator/**` остаётся за `SYSTEM_ADMIN`, а health-пробы — открыты (как сейчас).
DoD: новые метрики видны в тестовом scrape; дашборды/SLO обновлены как код; `mvn verify` зелёный. Коммит `feat(observability): ...` + `/checkpoint`.

## Фаза 5 — API и БД: aircraft-эндпоинты + ревью индексов

Агенты: `db-dev`, `database-reviewer`, `backend-dev`, `docs-agent`. Скиллы: `api-design`, `jpa-patterns`, `postgres-patterns`, `database-migrations`.
1. **Aircraft list API** (TODO из P7-3, PROGRESS.md): REST `/api/v1/aircraft` (список бортов/типов из существующих данных привязок) для UI aircraft-bindings. Вертикальный срез: (миграция только если реально нужна новая таблица — сперва проверь схему) + домен + контроллер за `VIEW_SEQUENCES` + тесты. Пагинация и фильтр по типу ВС.
2. `database-reviewer`: EXPLAIN ANALYZE горячих запросов (выборка активных инстансов, event log, messages по dedup_key) на демо-данных docker-compose; недостающие индексы — миграцией `V39+`.
3. Обнови `docs/openapi/openapi.json` (springdoc) и перегенерируй фронтовый клиент `npm run gen:api`.
DoD: эндпоинты под интеграционными тестами; OpenAPI и сгенерированный клиент синхронны; `database-reviewer` без CRITICAL/HIGH; `reviewer` PASS. Коммит `feat(api): ...` + `/checkpoint`.

## Фаза 6 — Frontend: aircraft-bindings UI + Playwright E2E

Агенты: `frontend-architect`, `ui-agent`, `e2e-runner`, `typescript-reviewer`/`react-reviewer`. Скиллы: `frontend-patterns`, `react-patterns`, `react-testing`, `e2e-testing`, `vite-patterns`.
1. **Aircraft-bindings UI** (закрывает TODO P7): привязка последовательности к бортам (tail number AN / flight id FI) через новый API фазы 5. Строгая типизация из сгенерированного OpenAPI-клиента, i18n RU/EN, vitest+RTL-тесты, a11y.
2. **Playwright smoke E2E** (`e2e-runner`, скилл `e2e-testing`): `frontend/e2e/` — сценарии: логин admin/admin → список последовательностей → открыть редактор React Flow → дашборд инстансов получает WebSocket-статус. Запуск против `docker-compose up`; конфиг с ретраями и трейс-артефактами. Демо-контекст: борт VP-BQR, рейс SU1234.
3. Полный verification loop фронтенда: `npm run check && npm run lint && npm run test:run && npm run build`.
DoD: aircraft-bindings работает и покрыт тестами; минимум 3 E2E-сценария зелёные локально; без `any`, без хардкода строк; `reviewer` PASS. Коммит `feat(frontend): ...` + `/checkpoint`.

## Фаза 7 — CI/CD: frontend и E2E становятся гейтами, docker build

Агенты: `devops-agent`, `reviewer`. Скиллы: `deployment-patterns`, `docker-patterns`, `github-ops`; при красном — `/build-fix`.
1. `ci.yml`, job `frontend`: убрать `continue-on-error: true`; добавить шаги `npm run lint` и `npm run test:run` (vitest сейчас в CI вообще не гоняется — 183+ теста не гейтят!).
2. Новый job `e2e`: поднять backend+БД (docker compose или сервисы), прогнать Playwright smoke, артефакты — трейсы/скриншоты.
3. Новый job `docker`: build backend `Dockerfile` и frontend-образа (multi-stage и non-root уже сделаны в P8-1 — проверь, не дублируй), `helm lint deploy/helm/eca-system`. Push в registry — только если задан секрет registry; нет секрета → build-only + блокер в BLOCKERS.md.
4. Помни: репо приватное, `gh` нет — каждый шаг workflow воспроизведи локально той же командой перед коммитом.
DoD: все локальные репродукции шагов CI зелёные; frontend-job гейтит; E2E-job описан и локально воспроизводим. Коммит `feat(ci): ...` + `/checkpoint`.

## Фаза 8 — Финальный quality gate и отчёт

Агенты: `code-reviewer`, `refactor-cleaner`, `doc-updater`, `docs-agent`, `compliance-agent`, `tech-lead`. Команды/скиллы: `/quality-gate`, `/code-review`, `/refactor-clean`, `/update-docs`, `agent-self-evaluation`.
1. `/quality-gate` + `/code-review` по diff всего прогона (`git diff <коммит-до-фазы-0>...HEAD`); CRITICAL/HIGH — чинить немедленно через `bug-fixer`.
2. `refactor-cleaner`: мёртвый код по прогону; удалить посторонний worktree `.claude/worktrees/strange-jang-f38717` (ветку `claude/strange-jang-f38717` предварительно осмотреть: нет уникальных коммитов → удалить, есть → в BLOCKERS.md).
3. `/update-docs`: `README.md` (quickstart), `ROADMAP.md` (итог прогона), `DEMO_SCRIPT.md`, `docs/PROGRESS.md` (закрытые backlog-пункты вычеркнуть), при архитектурных решениях (rate limiting, dedup-claim) — новые ADR в `docs/adr/` по шаблону.
4. `compliance-agent`: license report — новые зависимости (bucket4j, playwright) совместимы с Реестром.
5. Финальный `PRODUCTION_READINESS_REPORT.md`: таблица по 12 пунктам чек-листа (тестирование, наблюдаемость, безопасность, CI/CD, БД, архитектура, API, конфигурация, ошибки, frontend, документация, Docker/deploy) — статус, доказательство (файл/тест/отчёт), остаточные риски; отдельно — итог `BLOCKERS.md`.
6. Последний полный verification loop: backend `bash mvnw -B verify` + security-профиль, frontend полный, E2E.
DoD: quality gate PASS; отчёт создан; все доки синхронны; рабочее дерево чистое. Финальный коммит `docs(release): production readiness report` + `/checkpoint` + вывести отчёт по чек-листу в чат.
