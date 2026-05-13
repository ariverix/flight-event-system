package ru.protectinfotrans.eca.eventprocessor.dto;

import ru.protectinfotrans.eca.MessageType;

import java.time.LocalDateTime;

/**
 * DTO для ответа с данными сообщения.
 *
 * См. диплом: раздел 1.3.5 (UC-06)
 */
public record MessageResponse(
        Long id,
        MessageType messageType,
        String templateName,
        String aircraftId,
        String flightNumber,
        String content,
        LocalDateTime receivedAt
) {
}
