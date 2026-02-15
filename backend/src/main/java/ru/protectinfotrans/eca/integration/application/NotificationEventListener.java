package ru.protectinfotrans.eca.integration.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;

/**
 * Слушатель событий уведомлений о выполнении шагов.
 * Подписывается на StepNotificationEvent и отправляет уведомления операторам.
 *
 * Гарантия доставки: @ApplicationModuleListener использует Spring Modulith
 * Event Publication Registry (Transactional Outbox паттерн).
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Notify), раздел 1.4.1 (событийно-ориентированный паттерн)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationPort notificationPort;

    /**
     * Обработать событие уведомления о результате выполнения шага.
     * Вызывается когда Step имеет onSuccessNotify=true или onFailureNotify=true.
     */
    @ApplicationModuleListener
    public void onStepNotification(StepNotificationEvent event) {
        log.info("Received StepNotificationEvent: executionId={}, stepIndex={}, result={}, aircraft={}",
                event.executionId(), event.stepIndex(), event.isSuccess() ? "SUCCESS" : "FAILURE", event.aircraftId());

        String resultText = event.isSuccess() ? "succeeded" : "failed";
        String alertLevel = event.isSuccess() ? "INFO" : "HIGH";

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
