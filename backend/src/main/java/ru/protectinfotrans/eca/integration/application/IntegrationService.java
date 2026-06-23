package ru.protectinfotrans.eca.integration.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

import java.util.List;
import java.util.Map;

/**
 * Фасад для отправки сообщений и уведомлений операторам.
 *
 * <p><b>P3-3:</b> управление условиями (raise/close/query) больше НЕ часть этого класса — переехало
 * в отдельный модуль {@code conditions} ({@code ConditionService}, персистентное per-flight
 * хранилище с независимым уровнем алерта вместо прежнего in-memory
 * {@code Map<aircraftId, Set<conditionName>>}, см. {@code conditions.package-info} для полного
 * обоснования замены).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationService {

    private final MessageOutputPort messageOutputPort;
    private final NotificationPort notificationPort;

    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending uplink to aircraft={}, template={}", aircraftId, templateName);
        return messageOutputPort.sendUplink(aircraftId, templateName, params);
    }

    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        log.info("IntegrationService: sending ground message to recipients={}, template={}", recipients, templateName);
        return messageOutputPort.sendGround(recipients, templateName, params);
    }

    public void notifyOperator(String message, String alertLevel, String aircraftId) {
        log.info("IntegrationService: notifying operator about aircraft={}: {}", aircraftId, message);
        notificationPort.notifyStepResult(null, null, alertLevel, aircraftId, message);
    }
}
