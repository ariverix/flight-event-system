package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

/**
 * Событие перехода между шагами.
 * Фиксирует результат шага и принятое решение Result Decision Maker.
 *
 */
@Externalized("execution.step-transition::#{executionId}-#{fromStep}")
public record StepTransitionEvent(
        Long executionId,
        Integer fromStep,
        Integer toStep,
        StepResult result,
        TransitionAction action
) {
}
