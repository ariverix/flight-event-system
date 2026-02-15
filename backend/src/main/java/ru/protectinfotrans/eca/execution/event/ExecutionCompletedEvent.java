package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

/**
 * Событие завершения экземпляра выполнения.
 * finalStatus может быть COMPLETED (END) или ABORTED (ABORT).
 *
 * См. диплом: раздел 1.4.3 (информационные потоки)
 */
@Externalized("execution.completed::#{executionId}")
public record ExecutionCompletedEvent(
        Long executionId,
        ExecutionStatus finalStatus
) {
}
