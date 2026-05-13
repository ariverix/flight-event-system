package ru.protectinfotrans.eca.execution.dto;

import ru.protectinfotrans.eca.FlightStage;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Контекст выполнения шага — данные окружения для оценки критериев.
 *
 * См. диплом: раздел 1.3.3 (модель ECA), раздел 1.4.3
 */
public record ExecutionContext(
        String aircraftId,
        String flightNumber,
        FlightStage currentFlightStage,
        LocalDateTime currentTime,
        Map<String, Object> additionalData
) {
}
