package ru.protectinfotrans.eca.eventprocessor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.MessageType;

/**
 * DTO для приёма входящих сообщений от внешних систем.
 *
 * <p>{@code externalMessageId} — идентификатор сообщения от внешней ACARS-системы
 * (ARINC message reference, AFTN serial number и т.п.). Опционален на уровне протокола:
 * не все исторические/деградированные источники надёжно присылают такой идентификатор,
 * но когда он есть — используется как ключ идемпотентности шлюза (P2-1): повторный приём
 * с тем же {@code externalMessageId} не создаёт дублирующее сообщение и не публикует
 * повторно {@code NormalizedEvent}. Без него гарантия идемпотентности на уровне шлюза
 * не действует (остаётся только дедуп потребителя по {@code messages.id}, P1-7).
 */
public record IncomingMessageRequest(
        @NotNull MessageType messageType,
        @NotBlank String templateName,
        @NotBlank String aircraftId,
        String flightNumber,
        String metadataJson,
        String externalMessageId
) {
}
