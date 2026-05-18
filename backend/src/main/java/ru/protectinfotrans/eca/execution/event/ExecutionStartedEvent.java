package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;

/**
 * Событие запуска экземпляра выполнения последовательности.
 *
 */
@Externalized("execution.started::#{id}")
public record ExecutionStartedEvent(
        Long id,
        Long sequenceId,
        String aircraftId,
        String flightNumber
) {
}
