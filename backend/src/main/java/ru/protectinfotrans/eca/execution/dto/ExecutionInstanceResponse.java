package ru.protectinfotrans.eca.execution.dto;

import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO для экземпляра выполнения последовательности.
 *
 */
public record ExecutionInstanceResponse(
        Long id,
        Long sequenceId,
        String sequenceName,
        String aircraftId,
        String flightNumber,
        ExecutionStatus status,
        Integer currentStepIndex,
        String contextJson,
        LocalDateTime waitStartedAt,
        LocalDateTime waitTimeoutAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<StepExecutionResponse> stepExecutions
) {
}
