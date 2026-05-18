package ru.protectinfotrans.eca.integration.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

/**
 * Log-заглушка для отправки уведомлений операторам.
 * В MVP просто логирует уведомления, в перспективе — WebSocket для UI.
 *
 */
@Component
@Slf4j
public class LogNotificationAdapter implements NotificationPort {

    @Override
    public void notifyStepResult(Long executionId, Integer stepIndex, String result, String aircraftId, String message) {
        log.info("[NOTIFICATION] Execution {} / Step {} / Result {} / Aircraft {} - {}",
                executionId, stepIndex, result, aircraftId, message);
    }
}
