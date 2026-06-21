package ru.protectinfotrans.eca.integration.parser;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.integration.callsign.CallsignMatchingService;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Общий шаг конвейера приёма RAW-сообщения (P2-2/P2-4), используемый ОБОИМИ путями, которые
 * должны вести себя идентично:
 * <ul>
 *   <li>{@code RawMessageController#receiveRawMessage} — обычный приём;</li>
 *   <li>{@code DeadLetterQueueService#reprocess} (P2-6) — повторный прогон DLQ-записи.</li>
 * </ul>
 *
 * <p>Выделен сюда, а не оставлен приватным методом в контроллере (как было до P2-6), чтобы
 * ручной reprocess DLQ-записи проходил РОВНО ТУ ЖЕ нормализацию metadata + callsign-matching
 * (P2-4, часть 2), что и обычный приём — без риска незаметно разойтись двумя копиями одной
 * и той же логики.
 */
@Component
@RequiredArgsConstructor
public class RawMessageIngestSupport {

    private final CallsignMatchingService callsignMatchingService;

    /**
     * Собрать metadata-карту, которая передаётся в {@code MessageInputPort#receiveMessage} —
     * метаданные парсера + {@code externalMessageId} (если он есть в {@link ParsedMessage}).
     */
    public Map<String, Object> buildMetadata(ParsedMessage parsed) {
        Map<String, Object> metadata = parsed.metadata() == null ? null : new HashMap<>(parsed.metadata());
        if (parsed.externalMessageId() != null && !parsed.externalMessageId().isBlank()) {
            metadata = metadata == null ? new HashMap<>() : metadata;
            metadata.put("externalMessageId", parsed.externalMessageId());
        }
        return metadata;
    }

    /**
     * Попытаться сопоставить {@code parsed.flightNumber()} как позывной с FI через
     * {@link CallsignMatchingService} (P2-4, часть 2). См. javadoc
     * {@code RawMessageController#resolveFlightIdByCallsign} (исходное место, откуда вынесена
     * эта логика) для полного объяснения контракта "не ломает существующую привязку".
     */
    public String resolveFlightId(ParsedMessage parsed, String departureAirport, String arrivalAirport,
                                   LocalDate flightDate) {
        if (parsed.flightNumber() == null || parsed.flightNumber().isBlank()) {
            return parsed.flightNumber();
        }

        LocalDate onDate = flightDate != null ? flightDate : LocalDate.now();
        Optional<String> matchedFlightId = callsignMatchingService.resolveFlightId(
                parsed.flightNumber(), onDate, departureAirport, arrivalAirport);

        return matchedFlightId.orElse(parsed.flightNumber());
    }
}
