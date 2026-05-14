package ru.protectinfotrans.eca.eventprocessor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.MessageType;

/**
 * DTO для приёма входящих сообщений от внешних систем.
 *
 * См. диплом: раздел 1.3.5 (UC-06)
 */
public record IncomingMessageRequest(
        @NotNull MessageType messageType,
        @NotBlank String templateName,
        @NotBlank String aircraftId,
        String flightNumber,
        String metadataJson
) {
}
