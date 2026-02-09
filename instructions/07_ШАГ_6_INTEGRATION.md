# ШАГ 6: INTEGRATION ADAPTER (заглушки отправки для MVP)

## Что сделать:

Реализация UC-07 (Отправить исходящее сообщение). Для MVP — log-заглушки.

### Выходные порты (port/out/):

**MessageOutputPort** (диплом: таблица 1.6):
```java
/**
 * Порт отправки исходящих сообщений.
 * MVP: Log-заглушка. Перспектива: ACARS-адаптер, HTTP-адаптер.
 * См. диплом: раздел 1.4.4, таблица 1.6
 */
public interface MessageOutputPort {
    boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params);
    boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params);
}
```

**NotificationPort** (диплом: таблица 1.6):
```java
/**
 * Порт уведомления операторов.
 * MVP: Log-заглушка. Перспектива: WebSocket-адаптер.
 * См. диплом: раздел 1.4.4, таблица 1.6
 */
public interface NotificationPort {
    void notifyOperator(String message, String alertLevel, String aircraftId);
}
```

### Адаптеры-заглушки (adapter/out/):

```java
/**
 * Log-заглушка для отправки сообщений.
 * В продакшене заменяется на ACARS-адаптер или HTTP-адаптер.
 * См. диплом: Глава 3, раздел «Перспективы развития»
 */
@Component @Slf4j
public class LogMessageAdapter implements MessageOutputPort {
    @Override
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        log.info("[UPLINK] → aircraft={}, template={}, params={}", aircraftId, templateName, params);
        return true; // MVP — всегда успех
    }
    
    @Override
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        log.info("[GROUND] → recipients={}, template={}, params={}", recipients, templateName, params);
        return true;
    }
}
```

```java
/**
 * Log-заглушка для уведомлений операторов.
 * В продакшене заменяется на WebSocket-адаптер.
 */
@Component @Slf4j
public class LogNotificationAdapter implements NotificationPort {
    @Override
    public void notifyOperator(String message, String alertLevel, String aircraftId) {
        log.warn("[NOTIFICATION] [{}] aircraft={}: {}", alertLevel, aircraftId, message);
    }
}
```

### IntegrationService (application/):

Сервис-фасад, вызываемый из ActionStepRule:

```java
@Service @Slf4j
public class IntegrationService {
    private final MessageOutputPort messageOutput;
    private final NotificationPort notificationPort;
    
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) { ... }
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) { ... }
    public void raiseCondition(String aircraftId, String conditionName, String alertLevel) { ... }
    public void closeCondition(String aircraftId, String conditionName) { ... }
}
```

> **raiseCondition / closeCondition:** Для MVP храни активные условия в памяти (ConcurrentHashMap<String, Set<String>>) или в таблице БД. CriterionEvaluator для CONDITION_ACTIVE будет проверять эту коллекцию.

### Подписка на StepNotificationEvent:

```java
@Component @Slf4j
public class NotificationEventListener {
    @ApplicationModuleListener
    public void onStepNotification(StepNotificationEvent event) {
        notificationPort.notifyOperator(
            String.format("Step %d %s for aircraft %s", event.stepIndex(), 
                event.isSuccess() ? "succeeded" : "failed", event.aircraftId()),
            event.isSuccess() ? "INFO" : "HIGH",
            event.aircraftId()
        );
    }
}
```

## Критерий завершения:
ACTION-шаги вызывают адаптеры-заглушки, логи показывают корректные сообщения с параметрами. StepNotificationEvent обрабатывается. raiseCondition/closeCondition работают.

**Коммит:** `"Step 6: Integration Adapter — log-based stubs for MVP (UC-07)"`
