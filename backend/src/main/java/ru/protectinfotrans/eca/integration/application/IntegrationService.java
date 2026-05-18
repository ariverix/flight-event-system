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
 * Фасад для отправки сообщений и управления условиями (алертами).
 * Активные условия держим в памяти — CriterionEvaluator читает отсюда.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationService implements ConditionQueryPort {

    private final MessageOutputPort messageOutputPort;
    private final NotificationPort notificationPort;

    // in-memory, для прода можно перенести в Redis или отдельную таблицу
    private final Map<String, Set<String>> activeConditions = new ConcurrentHashMap<>();

    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending uplink to aircraft={}, template={}", aircraftId, templateName);
        return messageOutputPort.sendUplink(aircraftId, templateName, params);
    }

    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending ground message to recipients={}, template={}", recipients, templateName);
        return messageOutputPort.sendGround(recipients, templateName, params);
    }

    public boolean raiseCondition(String aircraftId, String conditionName, String alertLevel) {
        log.info("IntegrationService: raising condition '{}' for aircraft={}, level={}",
                conditionName, aircraftId, alertLevel);

        activeConditions.computeIfAbsent(aircraftId, k -> ConcurrentHashMap.newKeySet())
                .add(conditionName);

        return messageOutputPort.raiseCondition(aircraftId, conditionName, alertLevel);
    }

    public boolean closeCondition(String aircraftId, String conditionName) {
        log.info("IntegrationService: closing condition '{}' for aircraft={}", conditionName, aircraftId);

        Set<String> conditions = activeConditions.get(aircraftId);
        if (conditions != null) {
            conditions.remove(conditionName);
            // чистим запись целиком если conditions пуст —
            // иначе map растёт неограниченно при большом числе ВС
            if (conditions.isEmpty()) {
                activeConditions.remove(aircraftId);
            }
        }

        boolean success = messageOutputPort.closeCondition(aircraftId, conditionName);

        return success;
    }

    public boolean isConditionActive(String aircraftId, String conditionName) {
        Set<String> conditions = activeConditions.get(aircraftId);
        boolean active = conditions != null && conditions.contains(conditionName);

        log.debug("Checking condition '{}' for aircraft={}: {}", conditionName, aircraftId, active);

        return active;
    }

    public Set<String> getActiveConditions(String aircraftId) {
        return activeConditions.getOrDefault(aircraftId, Set.of());
    }

    public void notifyOperator(String message, String alertLevel, String aircraftId) {
        log.info("IntegrationService: notifying operator about aircraft={}: {}", aircraftId, message);
        notificationPort.notifyStepResult(null, null, alertLevel, aircraftId, message);
    }
}
