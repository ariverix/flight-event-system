# ECA System

Event-Condition-Action система для обработки авиационных сообщений.
Аналог модуля Sequencer из AIRCOM ServerPlatform (SITA).

## Назначение

Определение последовательности и времени выполнения действий вне обычного потока обработки сообщений ACARS.

## Технологический стек

### Backend
- Java 21
- Spring Boot 3.5
- Spring Modulith
- Spring Data JPA
- Spring Security (JWT)
- Easy Rules 4.1
- PostgreSQL 16
- Flyway

### Frontend
- React 18
- TypeScript 5
- Vite 6
- Ant Design 5
- React Flow

## Архитектура

- **Архитектурный стиль:** Модульный монолит
- **Паттерн организации кода:** Гексагональная архитектура (Порты и Адаптеры)
- **Паттерн взаимодействия:** Событийно-ориентированный (Spring Events)

### Модули
1. Sequence Manager — CRUD последовательностей
2. Execution Engine — движок ECA-правил
3. Event Processor — приём входящих сообщений
4. Integration Adapter — отправка исходящих сообщений
5. User — управление пользователями

## Запуск

### Через Docker Compose

```bash
docker-compose up --build
```

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

3. Запустить frontend:
```bash
cd frontend
npm install
npm run dev
```

## Доступ

- **Web UI:** http://localhost:8080
- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **Actuator:** http://localhost:8080/actuator/health

## Учётные данные

- **Логин:** admin
- **Пароль:** admin

## Автор

ФГУП «ЗащитаИнфоТранс»
