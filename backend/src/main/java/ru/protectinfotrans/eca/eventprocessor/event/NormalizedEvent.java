package ru.protectinfotrans.eca.eventprocessor.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;

import java.time.LocalDateTime;

/**
 * Нормализованное событие — результат классификации входящего сообщения.
 * Публикуется Event Processor'ом для обработки Execution Engine.
 *
 */
@Externalized("eventprocessor.normalized::#{messageId}")
public record NormalizedEvent(
        Long messageId,
        MessageType messageType,
        String templateName,
        String aircraftId,
        String flightNumber,
        FlightStage flightStage,
        LocalDateTime timestamp
) {
}
