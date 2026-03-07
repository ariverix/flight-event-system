# ECA System — Event-Condition-Action система обработки авиационных сообщений

Система определения последовательности и времени выполнения действий на основе модели ECA.
Аналог модуля Sequencer из платформы AIRCOM ServerPlatform (SITA), раздел 5.13.

**Заказчик:** ФГУП «ЗащитаИнфоТранс»
**Назначение:** Определение последовательности и времени выполнения действий вне обычного потока обработки сообщений ACARS.

---

## Технологический стек

### Backend
| Технология | Версия | Назначение |
|---|---|---|
| Java | 21 (LTS) | Язык, Virtual Threads, Records |
| Spring Boot | 3.5 | Базовый фреймворк |
| Spring Modulith | 1.3 | Модульная архитектура, Transactional Outbox |
| Spring Security | (в Boot) | JWT-аутентификация, 2 роли |
| Easy Rules | 4.1 | Движок ECA-правил |
| PostgreSQL | 16 | Основная СУБД, JSONB для параметров |
| Flyway | 10 | Версионирование схемы БД |
| springdoc-openapi | 2.8 | Swagger UI |

### Frontend
| Технология | Версия | Назначение |
|---|---|---|
| React | 18 | SPA |
| TypeScript | 5 | Статическая типизация |
| Vite | 6 | Сборщик |
| Ant Design | 5 | UI-компоненты |
| React Flow | 12 | Визуализация последовательностей (read-only) |

---

## Архитектура

- **Стиль:** Модульный монолит — 2 Docker-контейнера: приложение + PostgreSQL
- **Паттерн кода:** Гексагональная архитектура (Порты и Адаптеры)
- **Взаимодействие модулей:** Событийно-ориентированный (Spring ApplicationEvents)
- **Надёжность:** Transactional Outbox через Spring Modulith Event Publication Registry

### 5 модулей

| Модуль | Назначение |
|---|---|
| `sequence` | Sequence Manager — CRUD последовательностей, активация |
| `execution` | Execution Engine — движок ECA-правил, управление экземплярами |
| `eventprocessor` | Event Processor — приём ACARS-сообщений, публикация событий |
| `integration` | Integration Adapter — отправка исходящих сообщений (Log-заглушка) |
| `user` | User — управление пользователями, JWT-аутентификация |

---

## Запуск

### Через Docker Compose (рекомендуется)

```bash
docker-compose up --build
```

Первый запуск занимает несколько минут (сборка frontend + backend).
После запуска система доступна на http://localhost:8080.

### Локальная разработка

1. Запустить PostgreSQL:
```bash
docker-compose up postgres
```

2. Запустить backend:
```bash
cd backend
./mvnw spring-boot:run
```

3. Запустить frontend (отдельный терминал):
```bash
cd frontend
npm install
npm run dev
```
Frontend будет доступен на http://localhost:5173 с проксированием API на порт 8080.

---

## Доступ

| Адрес | Описание |
|---|---|
| http://localhost:8080 | Web UI |
| http://localhost:8080/swagger-ui.html | Swagger UI — документация API |
| http://localhost:8080/actuator/health | Actuator — состояние системы |
| http://localhost:8080/actuator/info | Actuator — информация о версии |
| http://localhost:8080/actuator/metrics | Actuator — метрики |

## Учётные данные по умолчанию

| Логин | Пароль | Роль |
|---|---|---|
| `admin` | `admin` | ADMIN |

---

## Демосценарий

Ниже описан полный сценарий проверки работы системы через UI.

### 1. Вход в систему
- Открыть http://localhost:8080
- Войти: логин `admin`, пароль `admin`

### 2. Создание последовательности (UC-01)
- Перейти в раздел **Последовательности**
- Нажать **Создать**
- Заполнить: название `Test OOOI Sequence`, описание по желанию
- Сохранить — последовательность создана в статусе **DRAFT**

### 3. Добавление шагов (UC-02, UC-03)
- Открыть созданную последовательность → **Добавить шаг**
- Шаг 1: тип **EVALUATE**, критерий `Flight stage = OFF` — при успехе CONTINUE, при неудаче GOTO 1
- Шаг 2: тип **ACTION**, действие `Send uplink message` — при успехе END, при неудаче ABORT
- Переходы настраиваются в форме редактирования шага

### 4. Активация (UC-04)
- Нажать **Активировать** — статус меняется на **ACTIVE**
- Теперь последовательность обрабатывает входящие события

### 5. Отправка тестового сообщения (UC-06)
- Перейти в раздел **Симулятор сообщений**
- Выбрать ВС (например, `SU-001`), тип `DOWNLINK`, шаблон `OOOI`
- Нажать **Отправить** — сообщение поступает в Event Processor

### 6. Просмотр выполнения (UC-05)
- Перейти в раздел **Выполнение**
- Убедиться, что появился экземпляр выполнения для `SU-001`
- Открыть детали — видна история переходов между шагами

### 7. Управление пользователями (UC-09)
- Перейти в раздел **Пользователи** (только для ADMIN)
- Создать нового пользователя с ролью OPERATOR

---

## Структура проекта

```
flight-event-system/
├── backend/                        # Spring Boot backend
│   ├── src/main/java/ru/protectinfotrans/eca/
│   │   ├── sequence/               # Sequence Manager (UC-01..UC-04)
│   │   │   ├── domain/             # Сущности: Sequence, Step
│   │   │   ├── port/in/            # Входные порты (use cases)
│   │   │   ├── port/out/           # Выходные порты (репозитории)
│   │   │   ├── adapter/in/         # REST-контроллер
│   │   │   └── adapter/out/        # JPA-адаптеры
│   │   ├── execution/              # Execution Engine (UC-05, UC-08)
│   │   │   ├── domain/             # ExecutionInstance, StepExecution
│   │   │   ├── application/        # ExecutionService, @Scheduled таймауты
│   │   │   └── rules/              # Easy Rules: ActionRule, EvaluateRule, WaitRule
│   │   ├── eventprocessor/         # Event Processor (UC-06)
│   │   │   ├── domain/             # IncomingMessage
│   │   │   └── application/        # EventProcessorService
│   │   ├── integration/            # Integration Adapter (UC-07)
│   │   │   └── adapter/out/        # LogMessageAdapter (MVP-заглушка)
│   │   ├── user/                   # User module (UC-09)
│   │   │   ├── domain/             # User, Role
│   │   │   ├── application/        # UserService, JwtService
│   │   │   └── adapter/in/         # AuthController, UserController
│   │   ├── AuditLog.java           # Сущность журнала аудита
│   │   ├── FlightStage.java        # Enum: INIT, OUT, OFF, ON, IN
│   │   ├── MessageType.java        # Enum: DOWNLINK, UPLINK, GROUND
│   │   └── SpaController.java      # SPA fallback для React Router
│   ├── src/main/resources/
│   │   ├── db/migration/           # Flyway миграции V1..V9
│   │   ├── application.yml         # Конфигурация
│   │   └── logback-spring.xml      # Настройка логирования
│   └── Dockerfile                  # (не используется напрямую — см. корневой Dockerfile)
├── frontend/                       # React SPA
│   ├── src/
│   │   ├── api/                    # Axios клиенты
│   │   ├── components/             # React-компоненты
│   │   ├── hooks/                  # useAuth, usePolling
│   │   └── types/                  # TypeScript типы
│   └── vite.config.ts
├── docs/
│   └── deployment.puml             # PlantUML Deployment Diagram
├── Dockerfile                      # Multi-stage: Node + JDK + JRE
└── docker-compose.yml              # 2 сервиса: app + postgres
```

---

## Варианты использования

| ID | Название | Актор |
|---|---|---|
| UC-01 | Создать последовательность | Оператор |
| UC-02 | Добавить шаг | Оператор |
| UC-03 | Настроить переходы | Оператор |
| UC-04 | Активировать последовательность | Оператор |
| UC-05 | Просмотреть статус выполнения | Оператор |
| UC-06 | Обработать входящее сообщение | Внешняя система |
| UC-07 | Отправить исходящее сообщение | Execution Engine |
| UC-08 | Обработать таймаут | Таймер |
| UC-09 | Управлять пользователями | Администратор |
