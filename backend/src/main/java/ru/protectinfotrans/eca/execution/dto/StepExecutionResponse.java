package ru.protectinfotrans.eca.execution.dto;

import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import java.time.LocalDateTime;

/**
 * DTO для истории выполнения шага.
 *
 * См. диплом: раздел 1.3.5 (UC-05 Просмотр статуса выполнения)
 */
public record StepExecutionResponse(
        Long id,
        Integer stepIndex,
        StepType stepType,
        StepResult result,
        TransitionAction transitionAction,
        Integer transitionTarget,
        String detailsJson,
        LocalDateTime executedAt
) {
}
