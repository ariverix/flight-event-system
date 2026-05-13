package ru.protectinfotrans.eca.eventprocessor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.FlightStage;

/**
 * DTO для уведомления об изменении стадии полёта.
 *
 * См. диплом: раздел 1.3.5 (UC-06)
 */
public record FlightStageChangeRequest(
        @NotBlank String aircraftId,
        String flightNumber,
        @NotNull FlightStage stage
) {
}
