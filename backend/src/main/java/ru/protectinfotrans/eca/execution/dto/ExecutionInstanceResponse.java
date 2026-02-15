package ru.protectinfotrans.eca.execution.dto;

import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO для экземпляра выполнения последовательности.
 *
 * См. диплом: раздел 1.3.5 (UC-05 Просмотр статуса выполнения)
 */
public record ExecutionInstanceResponse(
        Long id,
        Long sequenceId,
        String aircraftId,
        String flightNumber,
        ExecutionStatus status,
        Integer currentStepIndex,
        String contextJson,
        LocalDateTime waitStartedAt,
        LocalDateTime waitTimeoutAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        List<StepExecutionResponse> stepHistory
) {
}
