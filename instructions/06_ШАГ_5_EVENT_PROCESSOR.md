# ШАГ 5: EVENT PROCESSOR (приём сообщений, публикация событий)

## Что сделать:

Реализация UC-06 (Обработать входящее сообщение) и UC-08 (Обработать таймаут).

### REST API:

| Метод | URL | Описание | UC | Доступ |
|---|---|---|---|---|
| POST | /api/v1/messages/incoming | Принять входящее сообщение | UC-06 | permitAll (внешние системы) |
| POST | /api/v1/flights/stage-change | Уведомить об изменении стадии полёта | UC-06 | permitAll |
| GET | /api/v1/messages | Журнал сообщений | — | OPERATOR, ADMIN |

Пагинация для GET /api/v1/messages: page, size, aircraftId, messageType (фильтры)

### Порты:
- `MessageInputPort` (port/in/) — интерфейс приёма сообщений (диплом: таблица 1.6)
- `EventPublisherPort` (port/out/) — интерфейс публикации событий

### Сервисы:
- `EventProcessorService` (application/) — приём сообщения, сохранение в БД (таблица messages), классификация, публикация NormalizedEvent через Spring ApplicationEvent

### NormalizedEvent (event/):

```java
/**
 * Нормализованное событие — единый формат для всех типов входящих данных.
 * Публикуется через Spring Events, подхватывается Execution Engine.
 * См. диплом: раздел 1.4.3, поток 1 (входящие события)
 */
public record NormalizedEvent(
    String eventType,           // MESSAGE_RECEIVED, FLIGHT_STAGE_CHANGED, POSITION_REPORTED
    String aircraftId,
    String flightNumber,
    MessageType messageType,    // может быть null для stage-change
    String templateName,        // может быть null
    FlightStage flightStage,    // может быть null для message
    LocalDateTime timestamp,
    Map<String, Object> metadata
) {}
```

### Адаптеры:
- `MessageController` (adapter/in/) — REST контроллер
- `SpringEventPublisherAdapter` (adapter/out/) — реализация EventPublisherPort через ApplicationEventPublisher

### DTO:
- `IncomingMessageRequest` { messageType, templateName, aircraftId, flightNumber, content, metadata }
- `FlightStageChangeRequest` { aircraftId, flightNumber, stage }
- `MessageResponse` { id, messageType, templateName, aircraftId, flightNumber, content, receivedAt }

### Подписка Execution Engine на события:

В модуле `execution/`, создай слушатель:

```java
/**
 * Слушатель нормализованных событий.
 * Запускает обработку через ExecutionService.
 * См. диплом: раздел 1.4.1 (событийно-ориентированный паттерн)
 */
@Component
public class NormalizedEventListener {
    @ApplicationModuleListener  // Spring Modulith — гарантия доставки через Transactional Outbox
    public void onNormalizedEvent(NormalizedEvent event) {
        executionService.processEvent(event);
    }
}
```

### AuditLog:
Записывать MESSAGE_RECEIVED для каждого входящего сообщения.

## Критерий завершения:
Отправка сообщения через POST → сохранение в БД → публикация NormalizedEvent → ExecutionService.processEvent() вызывается. Журнал сообщений через GET с пагинацией работает. Unit-тесты на EventProcessorService.

**Коммит:** `"Step 5: Event Processor — message ingestion, NormalizedEvent, Spring Events (UC-06)"`
