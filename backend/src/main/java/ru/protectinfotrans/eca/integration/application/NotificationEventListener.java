package ru.protectinfotrans.eca.integration.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

// @ApplicationModuleListener гарантирует at-least-once доставку через Spring Modulith
// Event Publication Registry (Transactional Outbox)
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationPort notificationPort;

    @ApplicationModuleListener
    public void onStepNotification(StepNotificationEvent event) {
        log.info("Received StepNotificationEvent: executionId={}, stepIndex={}, result={}, aircraft={}",
                event.executionId(), event.stepIndex(), event.isSuccess() ? "SUCCESS" : "FAILURE", event.aircraftId());

        String resultText = event.isSuccess() ? "succeeded" : "failed";

        String message = String.format(
                "Step %d %s for aircraft %s (Execution #%d)",
                event.stepIndex(),
                resultText,
                event.aircraftId(),
                event.executionId()
        );

        notificationPort.notifyStepResult(
                event.executionId(),
                event.stepIndex(),
                event.isSuccess() ? "SUCCESS" : "FAILURE",
                event.aircraftId(),
                message
        );
    }
}
