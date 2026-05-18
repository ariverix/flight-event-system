package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

/**
 * Событие завершения экземпляра выполнения.
 * finalStatus может быть COMPLETED (END) или ABORTED (ABORT).
 *
 */
@Externalized("execution.completed::#{executionId}")
public record ExecutionCompletedEvent(
        Long executionId,
        ExecutionStatus finalStatus
) {
}
