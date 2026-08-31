# BLOCKERS — прогон «Промышленный апгрейд» (2026-07-03)

> Заблокированные пункты (нужен секрет/внешняя инфраструктура/решение заказчика).
> Работа не останавливается: блокер фиксируется здесь, прогон идёт дальше (правило 5 прогона).

## Внешняя инфраструктура / секреты

- **NVD_API_KEY для OWASP Dependency-Check (Фаза 3).** Локальный прогон
  `mvnw -Psecurity-scan dependency-check:check` без ключа упирается в жёсткий rate-limit NVD
  (или пустую базу CVE). В CI ключ задаётся секретом `NVD_API_KEY` (см. `.github/workflows/ci.yml`,
  PROGRESS.md CI-заметки). Локально SpotBugs/FindSecBugs и Semgrep воспроизводимы без ключа —
  ими и проверяемся. **Разблокировка:** задать `NVD_API_KEY` в окружении дев-машины.

- ~~**Push docker-образов в registry (Фаза 7).**~~ — **ЗАКРЫТО 2026-08-31**: push в GHCR
  (GitHub Container Registry, `ghcr.io/ariverix/flight-event-system-{backend,frontend}`) через
  встроенный `GITHUB_TOKEN` (`permissions.packages: write` на job `docker`) — отдельного секрета
  registry не потребовалось, GHCR интегрирован с GitHub Actions нативно. Push только на события
  `push` (не на PR). Первый push создаёт GHCR-пакет private — сделать публичным можно вручную в
  Settings пакета на GitHub, если нужно (не блокирует функциональность, только видимость извне).

- **Приёмочный перф-прогон на прод-железе / тюнингованном пуле (P6-3 follow-up, IMPROVEMENT_PLAN).**
  Требует приёмочный стенд заказчика — вне объёма прогона. Индикативные числа — `docs/perf/`.

- ~~**Branch protection: required status checks (Фаза 7, MEDIUM ревью).**~~ —
  **ЗАКРЫТО 2026-08-31** (решение Дениса): репозиторий переведён в public
  (`gh repo edit --visibility public`, GitHub Free не поддерживал branch protection на
  приватных репо — 403 "Upgrade to GitHub Pro or make this repository public"). На `main`
  включена защита ветки: required status checks (strict/up-to-date) —
  `Backend (build, test, Modulith-verify, coverage gate)`,
  `Frontend (typecheck, lint, vitest, build)`,
  `E2E (Playwright smoke против реального стека)`; force-push и удаление ветки запрещены.
  `security-scan`/`docker` в required checks не добавлены (не входили в решение) — можно
  добавить отдельным шагом при необходимости.

## Требуют решения Дениса

- ~~**Ветка `claude/strange-jang-f38717` — 18 уникальных коммитов, НЕ удалена (Фаза 8).**~~
  — **ЗАКРЫТО 2026-08-26** (решение Дениса): ветка удалена локально (`git branch -D`) и на
  `origin` (`git push origin --delete`). Ранняя история прототипа («Step 0…Step 10»,
  «version 0.1») ценности для текущего кода не имела (см. анализ Фазы 8 ниже).

## Решения по объёму (scope), зафиксированы при реализации

- **SpotBugs CRLF_INJECTION_LOGS — 138 находок Medium при threshold=Low (Фаза 3).**
  Гейт CI = threshold **High** — на нём находок 0 (сборка чистая). При Low-пороге FindSecBugs
  помечает КАЖДУЮ строку лога с не-константным строковым аргументом (в т.ч. внутренние ID, enum'ы,
  уже безопасные значения) в ~40 классах — большинство НЕ являются внешне-контролируемым вводом.
  **Сделано:** утилита `LogSanitizer` + санитизация реальной внешней атак-поверхности
  (открытый ингест `/messages` — MessageController; логин `/auth` — AuthController; username в
  UserService). **Сознательно НЕ сделано:** тотальная санитизация всех 138 строк —
  диспропорционально (внутренние ID/enum не форжатся злоумышленником) и технически неэффективно
  (FindSecBugs не распознаёт кастомный cleanser — находка остаётся даже после sanitize). Medium,
  гейт не валит. **Follow-up (по желанию заказчика):** сплошная санитизация или регистрация
  cleanser-паттерна FindSecBugs.

- **SpotBugs SQL_INJECTION_SPRING_JDBC — RetentionService (Medium, false-positive).**
  Динамический DDL управления партициями (`CREATE/DROP TABLE <partition>`): идентификатор таблицы
  НЕЛЬЗЯ параметризовать через `?`. Имя формируется из `YearMonth` (`partitionName`) и
  regex-ограниченного запроса pg_catalog (`getNamedPartitions`) — внешнего ввода нет.
  **Сделано (defense-in-depth):** явный whitelist-guard `assertSafePartitionName`
  (`^tracking_event_log_\d{4}_\d{2}$`) перед CREATE/DROP — страховка от будущего рефактора.

- **SpotBugs INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE — DeadLetterQueueService.stackTraceOf
    (Medium, intentional-by-design).** Стектрейс сбойного входящего сообщения сохраняется в DLQ
  для диагностики ОПЕРАТОРОМ; DLQ за RBAC `MANAGE_DLQ` (OPERATOR/ADMIN) — не раскрывается анонимно.
  Осознанная диагностическая фича, не утечка. Оставлено как есть.
