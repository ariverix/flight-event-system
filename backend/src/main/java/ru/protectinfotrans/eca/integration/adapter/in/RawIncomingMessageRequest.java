package ru.protectinfotrans.eca.integration.adapter.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;

/**
 * DTO для приёма СЫРОГО (нераспарсенного) сообщения в одном из промышленных форматов
 * «борт-земля» (P2-2): ARINC 618/620, Type B, AFTN.
 *
 * <p>Формат явно указывается вызывающей стороной — см.
 * {@link RawMessageFormat} (обоснование отказа от авто-детекта).
 * Сообщение нормализуется {@code RawMessageParserService} (этот же модуль {@code integration}) в
 * структуру борт/рейс/тип/payload/externalMessageId и передаётся в
 * {@code eventprocessor.port.in.MessageInputPort#receiveMessage} — идемпотентность/persist/публикация
 * события (P2-1) применяются без изменений, включая случай, когда {@code externalMessageId}
 * извлечён из самого сообщения (ARINC message reference / AFTN serial number).
 */
public record RawIncomingMessageRequest(
        @NotNull RawMessageFormat format,
        @NotBlank String rawMessage
) {
}
