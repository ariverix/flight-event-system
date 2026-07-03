# BLOCKERS — прогон «Промышленный апгрейд» (2026-07-03)

> Заблокированные пункты (нужен секрет/внешняя инфраструктура/решение заказчика).
> Работа не останавливается: блокер фиксируется здесь, прогон идёт дальше (правило 5 прогона).

## Внешняя инфраструктура / секреты

- **NVD_API_KEY для OWASP Dependency-Check (Фаза 3).** Локальный прогон
  `mvnw -Psecurity-scan dependency-check:check` без ключа упирается в жёсткий rate-limit NVD
  (или пустую базу CVE). В CI ключ задаётся секретом `NVD_API_KEY` (см. `.github/workflows/ci.yml`,
  PROGRESS.md CI-заметки). Локально SpotBugs/FindSecBugs и Semgrep воспроизводимы без ключа —
  ими и проверяемся. **Разблокировка:** задать `NVD_API_KEY` в окружении дев-машины.

- **Push docker-образов в registry (Фаза 7).** Секрет registry не задан. План: build-only
  (backend `Dockerfile` + frontend multi-stage) + `helm lint`, без push. **Разблокировка:**
  секрет registry (URL + credentials) в CI.

- **Приёмочный перф-прогон на прод-железе / тюнингованном пуле (P6-3 follow-up, IMPROVEMENT_PLAN).**
  Требует приёмочный стенд заказчика — вне объёма прогона. Индикативные числа — `docs/perf/`.

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
