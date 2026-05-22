package ru.protectinfotrans.eca.execution.dto;

import ru.protectinfotrans.eca.FlightStage;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Контекст выполнения шага — данные окружения для оценки критериев.
 *
 */
public record ExecutionContext(
        String aircraftId,
        String flightNumber,
        FlightStage currentFlightStage,
        LocalDateTime currentTime,
        Map<String, Object> additionalData
) {
}
