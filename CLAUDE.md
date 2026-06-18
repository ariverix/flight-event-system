# ACARS ECA System

## Стек

- Java 21, Spring Boot 3.5, Spring Modulith, Easy Rules 4.1
- PostgreSQL 16 + Flyway (10 миграций V1–V10, 8 таблиц)
- React 18 + TypeScript, Ant Design 5, React Flow
- Docker Compose: eca-app:8080, eca-postgres:5432, БД eca_db
- JWT 24ч, BCrypt паролей

## Модули backend (5)

- `eventprocessor` — приём ACARS-сообщений
- `sequence` — CRUD сценариев
- `execution` — ECA-движок (CONTINUE/GOTO/END/ABORT)
- `integration` — адаптер ACARS
- `user` — JWT-авторизация

## Текущий статус

- 119 тестов, покрытие JaCoCo 72% → цель 85%
- 3 типа шагов: ACTION, WAIT, EVALUATE
- 6 типов критериев
- ~25000 строк, 272 файла

## Команды

- Перед тестами: `docker start eca-postgres` (если контейнер не поднят — интеграционные тесты падают с ApplicationContext/Connection refused на localhost:5432)
- Первый раз после создания контейнера нужно создать БД `eca_test` (отдельная от `eca_db`, используется `BaseIntegrationTest`): `docker exec eca-postgres psql -U eca_user -d eca_db -c "CREATE DATABASE eca_test OWNER eca_user;"`
- Backend: `cd backend && mvn spring-boot:run`
- Frontend: `cd frontend && npm start`
- Тесты: `cd backend && mvn test`
- JaCoCo отчёт: `target/site/jacoco/index.html`
- Docker: `docker-compose up`

## Правила

- Каждое изменение Java = тест обязателен
- Новая таблица = новая Flyway-миграция (не редактировать V1-V10)
- Модули общаются только через Spring Modulith Events
- Покрытие не должно падать ниже 72%

## Агенты

- `@backend-dev` — новые фичи и баги
- `@qa-agent` — тесты и покрытие
- `@reviewer` — проверка качества перед коммитом