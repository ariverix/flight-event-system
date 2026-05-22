package ru.protectinfotrans.eca.eventprocessor.dto;

import ru.protectinfotrans.eca.MessageType;

import java.time.LocalDateTime;

/**
 * DTO для ответа с данными сообщения.
 *
 */
public record MessageResponse(
        Long id,
        MessageType messageType,
        String templateName,
        String aircraftId,
        String flightNumber,
        LocalDateTime receivedAt,
        String metadataJson
) {
}
