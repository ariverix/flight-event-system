package ru.protectinfotrans.eca.integration.parser.format;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.integration.parser.MessageParser;
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ARINC 620 — Ground-Ground Data Exchange Standard: обмен ACARS-сообщениями МЕЖДУ наземными
 * системами (в отличие от 618 — air/ground). На практике это пересылка ранее принятого с борта
 * (или адресованного на борт) сообщения между наземными узлами сети (например DSP/АЭРОФЛОТ ->
 * наша система) с дополнительным заголовком отправитель/получатель наземного узла:
 * <pre>
 * ORIG/DSP-MOW DEST/ZASHITAINFOTRANS
 * AN/VP-BQR FI/SU1234 LABEL/Q0
 * MSGREF/620-2026-000451
 * OUT/1245
 * </pre>
 * Тело сообщения (борт/рейс/label/OOOI/позиция) разбирается ТЕМИ ЖЕ полями, что и 618 —
 * 620 переносит то же сообщение между наземными узлами, не меняя его внутреннюю структуру,
 * поэтому парсер делегирует разбор тела {@link Arinc618Parser} и переопределяет только
 * направление (всегда {@link MessageType#GROUND} — это всегда обмен земля-земля независимо
 * от заголовка DIR, если он зачем-то присутствует) и добавляет ORIG/DEST в metadata.
 */
@Component
@Slf4j
public class Arinc620Parser implements MessageParser {

    private static final Pattern ORIG_PATTERN = Pattern.compile("\\bORIG[/ ]([A-Z0-9-]{2,32})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEST_PATTERN = Pattern.compile("\\bDEST[/ ]([A-Z0-9-]{2,32})\\b", Pattern.CASE_INSENSITIVE);

    private final Arinc618Parser bodyParser;

    public Arinc620Parser(Arinc618Parser bodyParser) {
        this.bodyParser = bodyParser;
    }

    @Override
    public RawMessageFormat supportedFormat() {
        return RawMessageFormat.ARINC_620;
    }

    @Override
    public ParsedMessage parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new MessageParsingException(RawMessageFormat.ARINC_620, "ARINC 620: пустое сообщение");
        }

        String normalized = rawMessage.strip();

        Matcher origMatcher = ORIG_PATTERN.matcher(normalized);
        Matcher destMatcher = DEST_PATTERN.matcher(normalized);
        if (!origMatcher.find() || !destMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.ARINC_620,
                    "ARINC 620: не найден обязательный заголовок наземного узла (ORIG/.. DEST/..)");
        }
        String orig = origMatcher.group(1).toUpperCase();
        String dest = destMatcher.group(1).toUpperCase();

        ParsedMessage body;
        try {
            body = bodyParser.parse(normalized);
        } catch (MessageParsingException bodyParsingFailed) {
            throw new MessageParsingException(RawMessageFormat.ARINC_620,
                    "ARINC 620: тело сообщения не распознано (" + bodyParsingFailed.getMessage() + ")",
                    bodyParsingFailed);
        }

        Map<String, Object> metadata = body.metadata() == null ? new HashMap<>() : new HashMap<>(body.metadata());
        metadata.put("groundOrig", orig);
        metadata.put("groundDest", dest);

        // ground-ground всегда GROUND независимо от исходного DIR борта внутри тела —
        // это обмен между наземными узлами по определению формата.
        return new ParsedMessage(MessageType.GROUND, body.templateName(), body.aircraftId(),
                body.flightNumber(), body.payload(), body.externalMessageId(), metadata);
    }
}
