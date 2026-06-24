# CLAUDE.md — общие правила проекта flight-event-system

> Этот файл Claude Code загружает автоматически в каждую сессию и для каждого агента.
> Здесь — инварианты, которые НЕ обсуждаются. Детали ролей — в `.claude/agents/*`, план — в `ROADMAP.md`, команда — в `TEAM.md`.

## Что это за проект
Отечественная промышленная замена модуля **SITA AIRCOM Sequencer** — система обработки авиационных событий по сообщениям «борт-земля» (ACARS). Движок правил ECA (Event-Condition-Action). Заказчик — ФГУП «ЗащитаИнфоТранс».
Репозиторий: `ariverix/flight-event-system`, ветка `main`. Локальный запуск: `docker-compose up --build`, http://localhost:8080, admin/admin.

## Стек (не менять без ADR архитектора)
Backend: Java 21, Spring Boot 3.5, Spring Modulith, Easy Rules 4.1, PostgreSQL 16, Flyway.
Frontend: React 18, TypeScript 5, Ant Design 5, React Flow 12.
Инфра: Docker (цель — Kubernetes/Helm), GitHub Actions CI/CD.
Модули backend: `sequence`, `execution`, `eventprocessor`, `integration`, `user`. Архитектура: модульный монолит + гексагональная (порты/адаптеры).

## Канонические метрики (держать согласованными во всех документах)
8 таблиц БД; миграции от V1–V10 (новые — Vxx по возрастанию); базово 119 тестов (78 unit + 32 integration + 9 architecture), JaCoCo 72% — **цель ≥ 85%**; 3 типа шагов, 6 типов критериев; демо-сценарий: борт VP-BQR, рейс SU1234.

## Спецификация паритета с SITA (реализуем РОВНО так — не упрощать)
**3 типа шагов:** ACTION (raise/close condition + уровень алерта, send uplink computer-generated|external-user + шаблон, send ground + получатели, wait {x}{sec/min/hour}); EVALUATE IF (мгновенная проверка критериев); WAIT FOR (блокирует до выполнения критериев, таймаут→false, чекбокс «from this point only»).
**6 типов критериев** (комбинируются AND/OR): message received (downlink/uplink/ground + шаблон + from-this-point-only); flight stage (=,>,<,>=,<=,not × Init/Out/Off/On/In/Summary); position (reported|not + in last {x} min; источники ACARS/radar/ADS-B; оценочные игнорируются); time (before|equal|after × ETD/ETA/Init/Out/Off/On/In ± {x} min).
**Решения** (отдельно для true и false): CONTINUE / GOTO step {x} / END / ABORT, + чекбокс Notify.
**Start/Stop criteria** на всю последовательность — непрерывная оценка. **Привязка** к борту: tail number (AN) ИЛИ flight id (FI)+flight data. Много последовательностей на борт; одна на много бортов (свой указатель шага). Active/inactive; фильтр по бортам/типам; папки; Event Log класса Tracking; Event Handling (folder/sequence); уровни алертов No/Low/Medium/High/Critical; шаблоны computer-generated|external-user; custom fields; callsign matching table (ICAO code, даты, дни недели, dep/arr airport).

## Рабочий протокол (обязательный)
1. **TDD**: сначала тест → потом реализация → зелёное.
2. **Вертикальный срез**: задача = фича целиком (миграция + домен + API + тест), не «слой».
3. **БД/бэкенд меняем свободно**, НО только через тикет + новую Flyway-миграцию + тесты + ревью. Никаких ALTER в обход Flyway, никаких правок применённых миграций.
4. **Границы модулей**: межмодульно — через публичный API модуля или Modulith Events. Тест `ApplicationModules.verify()` зелёный и в CI.
5. **Гейт ревью обязателен**: задача `done` только если — тесты зелёные → coverage ≥ 85% (изменённое) → `reviewer` дал PASS → OpenAPI/доки обновлены → метрики/логи на новом пути добавлены.
6. **Красное → `bug-fixer`**, кросс-модульный дизайн → `architect`, декомпозиция → `tech-lead`.
7. **Безопасность**: секретов в коде/логах нет; эндпоинт приёма ACARS открыт (внешняя машина без JWT) и защищён сетево (mTLS/allowlist) — это осознанное решение; остальные пути за JWT/RBAC.
8. **Одна задача за раз**: после задачи — STOP и краткий отчёт. Денис гоняет задачи по очереди.

## Стиль кода
Backend: конструкторная инъекция (без `@Autowired` на полях), DTO ≠ Entity, единый `@ControllerAdvice`, минимум абстракций «на будущее».
Frontend: строгая типизация (без any), состояние через выбранный стор, контракт API только из OpenAPI, i18n RU/EN (без хардкода строк).

## Импортозамещение
Стек и зависимости — пригодные для Реестра российского ПО (совместимые лицензии). Путь к ГОСТ TLS для прод-контура. Без жёсткой привязки к зарубежным проприетарным облакам.
