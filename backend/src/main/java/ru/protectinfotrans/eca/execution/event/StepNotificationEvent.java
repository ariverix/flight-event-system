package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.execution.domain.StepResult;

/**
 * Событие уведомления о результате шага.
 * Публикуется когда onSuccessNotify или onFailureNotify = true.
 *
 */
@Externalized("execution.step-notification::#{executionId}-#{stepIndex}")
public record StepNotificationEvent(
        Long executionId,
        Integer stepIndex,
        StepResult result,
        String aircraftId,
        boolean isSuccess
) {
}
