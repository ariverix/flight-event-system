package ru.protectinfotrans.eca.integration.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.protectinfotrans.eca.execution.port.out.ConditionQueryPort;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис-фасад для интеграции с внешними системами.
 * Реализует UC-07 (Отправить исходящее сообщение).
 *
 * Обязанности:
 * - Отправка uplink/ground сообщений через MessageOutputPort
 * - Управление пользовательскими условиями (raise/close)
 * - Хранение активных условий в памяти (для CriterionEvaluator)
 * - Делегирование уведомлений через NotificationPort
 *
 * См. диплом: раздел 1.3.5 (UC-07), раздел 1.4.4 (таблица 1.6)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationService implements ConditionQueryPort {

    private final MessageOutputPort messageOutputPort;
    private final NotificationPort notificationPort;

    /**
     * Хранилище активных условий.
     * Key: aircraftId, Value: Set<conditionName>
     *
     * Для MVP используем ConcurrentHashMap (in-memory).
     * В продакшене можно заменить на таблицу БД или Redis.
     */
    private final Map<String, Set<String>> activeConditions = new ConcurrentHashMap<>();

    /**
     * UC-07: Отправить uplink сообщение на борт.
     */
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending uplink to aircraft={}, template={}", aircraftId, templateName);
        return messageOutputPort.sendUplink(aircraftId, templateName, params);
    }

    /**
     * UC-07: Отправить ground сообщение наземной службе.
     */
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending ground message to recipients={}, template={}", recipients, templateName);
        return messageOutputPort.sendGround(recipients, templateName, params);
    }

    /**
     * Поднять пользовательское условие (алерт) для ВС.
     * Сохраняет условие в памяти для проверки через CONDITION_ACTIVE критерий.
     */
    public boolean raiseCondition(String aircraftId, String conditionName, String alertLevel) {
        log.info("IntegrationService: raising condition '{}' for aircraft={}, level={}",
                conditionName, aircraftId, alertLevel);

        // Добавляем условие в активные
        activeConditions.computeIfAbsent(aircraftId, k -> ConcurrentHashMap.newKeySet())
                .add(conditionName);

        // Делегируем логирование в адаптер
        boolean success = messageOutputPort.raiseCondition(aircraftId, conditionName, alertLevel);

        // Опционально: можно опубликовать событие ConditionRaisedEvent

        return success;
    }

    /**
     * Снять пользовательское условие для ВС.
     */
    public boolean closeCondition(String aircraftId, String conditionName) {
        log.info("IntegrationService: closing condition '{}' for aircraft={}", conditionName, aircraftId);

        // Удаляем условие из активных
        Set<String> conditions = activeConditions.get(aircraftId);
        if (conditions != null) {
            conditions.remove(conditionName);
            if (conditions.isEmpty()) {
                activeConditions.remove(aircraftId);
            }
        }

        boolean success = messageOutputPort.closeCondition(aircraftId, conditionName);

        return success;
    }

    /**
     * Проверить, активно ли пользовательское условие для ВС.
     * Используется CriterionEvaluator для CONDITION_ACTIVE критерия.
     */
    public boolean isConditionActive(String aircraftId, String conditionName) {
        Set<String> conditions = activeConditions.get(aircraftId);
        boolean active = conditions != null && conditions.contains(conditionName);

        log.debug("Checking condition '{}' for aircraft={}: {}", conditionName, aircraftId, active);

        return active;
    }

    /**
     * Получить все активные условия для ВС.
     */
    public Set<String> getActiveConditions(String aircraftId) {
        return activeConditions.getOrDefault(aircraftId, Set.of());
    }

    /**
     * Отправить уведомление оператору.
     */
    public void notifyOperator(String message, String alertLevel, String aircraftId) {
        log.info("IntegrationService: notifying operator about aircraft={}: {}", aircraftId, message);
        notificationPort.notifyStepResult(null, null, alertLevel, aircraftId, message);
    }
}
