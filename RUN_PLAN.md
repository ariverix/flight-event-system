# RUN_PLAN — пошаговые промты (по одному за раз)

Как пользоваться: сначала вставил `00_FACTORY_PROMPT.md`. Дальше кидаешь в Claude Code промты ОТСЮДА по одному, ждёшь зелёного отчёта и `reviewer` PASS, потом следующий. Каждый промт = один вертикальный срез. Оркестратор сам делегирует профильному агенту.

Шаблон, если хочешь добавить свой шаг:
`Задача [ID]: <что сделать>. Веди [agent]. По TDD, затем qa/test-engineer и reviewer. Закрывай только по DoD. Потом STOP и отчёт.`

---

## P0 — Фундамент
**P0-1** Подними CI/CD каркас в GitHub Actions: build → `./gradlew test` → JaCoCo coverage gate ≥85% → шаг Modulith-verify. Веди devops-agent (gate-тест с architect). PASS reviewer. STOP+отчёт.
**P0-2** Добавь тест границ модулей `ApplicationModules.of(App).verify()` и почини текущие нарушения границ, если есть. Веди architect (правки — backend-dev). STOP.
**P0-3** Подключи springdoc, сгенерируй OpenAPI на текущее API + Swagger UI. Веди docs-agent. STOP.
**P0-4** Каркас структурных логов (JSON) + correlationId (борт/рейс/инстанс/сообщение) сквозным фильтром; пустой audit_log-каркас (таблица через db-dev, новая миграция). Веди observability-agent + db-dev. STOP.
**P0-5** Заведи `docs/adr/` и ADR-0001 «модульный монолит vs микросервисы» (обоснование монолита). Заведи `docs/PROGRESS.md` ledger. Веди architect + tech-lead. STOP.
**P0-6** Threat model (черновик) + чек-лист требований ФСТЭК + license report зависимостей в CI. Веди security-agent + compliance-agent. STOP.

## P1 — Промышленное ядро движка
**P1-1** Проверь и доведи до полного паритета SITA реализацию 3 типов шагов (ACTION/EVALUATE/WAIT) и 6 типов критериев с операторами и AND/OR. Где упрощено — допили. Сценарные тесты на каждый тип. Веди sequence-engine-dev. STOP.
**P1-2** Решения CONTINUE/GOTO/END/ABORT (отдельно для true и false) + чекбокс Notify; start/stop-критерии с непрерывной оценкой на всю последовательность. Тесты ветвления и GOTO назад/вперёд. Веди sequence-engine-dev. STOP.
**P1-3** Персистентный стейт инстанса: таблица `sequence_instance` (текущий шаг, контекст, статус, таймауты) + миграция; репозиторий; сохранение на каждом переходе. Веди db-dev (схема) + sequence-engine-dev (логика). STOP.
**P1-4** Resume после рестарта: при старте приложения незавершённые инстансы восстанавливаются и продолжают с текущего шага. Тест test-engineer: стоп сервиса с активными инстансами → старт → продолжили. Веди sequence-engine-dev + test-engineer. STOP.
**P1-5** Durable-планировщик WAIT/таймаутов (БД-backed, напр. ShedLock/Quartz), не in-memory. Тест: таймаут срабатывает один раз и переживает рестарт. Веди sequence-engine-dev. STOP.
**P1-6** Конкурентность: оптимистические блокировки на инстансах, корректное срабатывание одного события на много инстансов без гонок. Нагрузочный микротест test-engineer. Веди sequence-engine-dev. STOP.
**P1-7** Transactional Outbox (`event_publication`) + идемпотентный приём событий движком. ADR Outbox vs прямой вызов. Веди architect + sequence-engine-dev + db-dev. STOP.
**P1-8** Event Log класса Tracking: логировать завершение каждого шага (если включён флаг логирования последовательности), старт/стоп последовательности. Веди observability-agent. STOP.

## P2 — Интеграция ACARS
**P2-1** Входящий шлюз ACARS: эндпоинт приёма (открытый, защита сетевая) → нормализация → доменное событие `MessageReceived`. Идемпотентность по идентификатору сообщения (persist раньше обработки). Веди integration-dev. STOP.
**P2-2** Парсеры форматов ARINC 620/618 / Type B / AFTN: извлечение борта, рейса, типа сообщения, payload. Тесты на реальных примерах. Веди integration-dev. STOP.
**P2-3** Исходящий шлюз: отправка uplink и ground по команде движка (ACTION send uplink/ground), через Outbox. Веди integration-dev. STOP.
**P2-4** Разбор позывных + таблица соответствия `callsign_matching` (ICAO carrier code, start/end date, дни недели, dep/arr airport, specificity) → определение FI. Миграция + тесты. Веди integration-dev + db-dev. STOP.
**P2-5** Источники позиций ACARS/ADS-B/radar; только фактические, оценочные помечаются и игнорируются в position-критериях. «not reported» использует Off-таймстамп. Веди integration-dev. STOP.
**P2-6** DLQ для сбойных входящих + ручной reprocess; ретраи с backoff + circuit breaker на внешние каналы. Сценарий test-engineer. Веди integration-dev. STOP.
**P2-7** Нагрузочный замер входящего потока (k6/Gatling): целевой throughput msg/s и кол-во одновременных рейсов, p95/p99, точка деградации. Отчёт в `docs/perf/`. Веди test-engineer. STOP.

## P3 — Шаблоны / поля / алерты / уведомления
**P3-1** Движок шаблонов: downlink/uplink/ground, режимы computer-generated и from-external-user («when triggered by the Sequencer»), категории, подстановка переменных, рендеринг в формат канала. CRUD + OpenAPI. Веди templates-dev. STOP.
**P3-2** Движок custom fields: правила извлечения значений из входящих сообщений, хранение per-flight, подстановка в исходящие шаблоны и в критерии; закрытие контекста при завершении рейса. Веди templates-dev + db-dev. STOP.
**P3-3** Условия/алерты: raise/close custom condition (нельзя поднять дважды одним именем), уровни No/Low/Medium/High/Critical (условие и алерт независимы), авто-закрытие активных условий при завершении рейса. Тесты. Веди alerts-dev. STOP.
**P3-4** Event Handling (folder-level с наследованием + sequence-level переопределение) + Notify-каналы (email/webhook), идемпотентная доставка уведомлений на success/false шага. Веди alerts-dev + db-dev. STOP.

## P4 — Безопасность
**P4-1** RBAC user-rights по образцу SITA (Manage sequences, edit Aircraft и др.): роли→права→проверки на эндпоинтах. Тесты доступа можно/нельзя. Веди security-agent + db-dev. STOP.
**P4-2** JWT доступа + refresh с ротацией/инвалидизацией, BCrypt; ADR по токенам/крипто. Веди security-agent. STOP.
**P4-3** Вынос секретов из кода/репозитория (env/secret manager), чистка логов от чувствительного; проектирование пути к ГОСТ TLS. Веди security-agent + devops-agent. STOP.
**P4-4** OWASP Dependency-Check + SAST (Semgrep/FindSecBugs) в CI, фейл сборки на High/Critical. Веди security-agent + devops-agent. STOP.
**P4-5** Полный audit_log действий пользователя (логин, изменение последовательности/прав) — кто/что/когда. Веди security-agent + db-dev. STOP.

## P5 — Наблюдаемость и надёжность
**P5-1** Метрики Micrometer→Prometheus: активные инстансы, msg/s, ошибки парсинга, размер DLQ, отправленные uplink/ground, поднятые/закрытые условия, сработавшие таймауты, латентность обработки p95/p99, пул БД, JVM. Веди observability-agent. STOP.
**P5-2** Трассировка OpenTelemetry сквозная (входящее → движок → исходящее) с корреляцией по борту/рейсу/инстансу. Веди observability-agent. STOP.
**P5-3** Health liveness/readiness/startup (Actuator), readiness учитывает БД и критичные каналы. Дашборды Grafana + алерт-правила (рост DLQ, падение throughput, ошибки доставки) + SLO — как код в `observability/`. Веди observability-agent. STOP.
**P5-4** Бэкап/восстановление БД (pg_dump/PITR) + тест восстановления; chaos/failover тесты (падение канала, отказ реплики). Веди db-dev + devops-agent + test-engineer. STOP.

## P6 — Масштаб
**P6-1** HA: несколько реплик backend; leader election планировщика (single-fire в кластере). Тест: 2+ реплики, таймаут срабатывает один раз. Веди devops-agent. STOP.
**P6-2** Горизонтальное масштабирование (requests/limits, автоскейл) + партиционирование/retention больших таблиц (event log, messages, audit) по времени. Веди devops-agent + db-dev. STOP.
**P6-3** Полный нагрузочный прогон до целевых чисел + профилирование горячих путей + тюнинг. Отчёт с p95/p99 и throughput. Веди test-engineer (фиксы — sequence-engine-dev/bug-fixer). STOP.

## P7 — Промышленный фронтенд
**P7-1** Каркас FE: структура слоёв, стор, API-клиент из OpenAPI (типы из контракта), WebSocket-слой подписок; ADR фронтенда. Веди frontend-architect. STOP.
**P7-2** Редактор React Flow: ноды шагов (ACTION/EVALUATE/WAIT), рёбра-решения, drag-n-drop с авто-обновлением GOTO, панель start/stop-критериев. Веди ui-agent. STOP.
**P7-3** Формы всех типов шагов и 6 критериев с операторами/AND-OR, выбор шаблонов, уровни алертов, привязка к бортам/типам, active/inactive, папки; клиентская валидация (невалидное собрать нельзя). Веди ui-agent. STOP.
**P7-4** Реал-тайм: статусы запущенных инстансов + Event Log через WebSocket + таймлайн истории выполнения. Веди ui-agent. STOP.
**P7-5** i18n RU/EN полный, роль-зависимый UI (скрытие по RBAC), a11y, скелетоны/анимации. Веди frontend-architect + ui-agent. STOP.

## P8 — Упаковка, деплой, доки, приёмка
**P8-1** Kubernetes/Helm чарты (backend/frontend/PostgreSQL), пробы, ConfigMap/Secret, rolling/blue-green, контролируемое применение Flyway при деплое. Веди devops-agent. STOP.
**P8-2** Руководство администратора (RU) по образцу SITA ASP для нашего модуля + ранбуки эксплуатации (деплой, бэкап, инциденты, мониторинг) + README. Веди docs-agent. STOP.
**P8-3** Матрица паритета SITA→наше→тест (UAT) по всем фичам Sequencer + license report + материалы для Реестра российского ПО. Веди compliance-agent. STOP.
**P8-4** Приёмочный прогон по матрице паритета: проверить каждый пункт сценарным тестом/демо, закрыть пробелы. Финальный отчёт готовности к внедрению. Веди tech-lead + compliance-agent. STOP.
