package ru.protectinfotrans.eca.eventprocessor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.FlightStage;

/**
 * DTO для уведомления об изменении стадии полёта.
 *
 */
public record FlightStageChangeRequest(
        @NotBlank String aircraftId,
        String flightNumber,
        @NotNull @JsonProperty("newStage") FlightStage stage
) {
}
