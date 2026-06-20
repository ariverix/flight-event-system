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
 * AFTN — Aeronautical Fixed Telecommunication Network телеграмма (ICAO Annex 10).
 *
 * <p>Реальная структура AFTN-телеграммы:
 * <pre>
 * GG UUEEZRZX UUWWZDZX
 * 191045 UUWWZTZX
 * AN/VP-BQR
 * FI/SU1234
 * MVT AA1234/19 UUEE AA1230 AA1238 0
 * </pre>
 * где:
 * <ul>
 *   <li>1-я строка — priority indicator ({@code FF} обычный, {@code GG} срочный flight safety,
 *       {@code KK}, {@code SS}, {@code DD} срочный/чрезвычайный) + список адресатов
 *       (8-символьные ICAO-style адреса AFTN, разделённые пробелом);</li>
 *   <li>2-я строка — filing time (DDHHMM) + origin-индикатор (адрес отправителя/станции
 *       подачи), здесь же исторически передаётся порядковый номер AFTN-сообщения
 *       (serial number) станции отправителя — в данном текстовом профиле явно через
 *       {@code SER/};</li>
 *   <li>{@code AN/}, {@code FI/} — те же служебные поля борт/рейс, что и в других форматах;</li>
 *   <li>остаток — текст телеграммы.</li>
 * </ul>
 * Как и Type B, AFTN — обмен между наземными системами (АДП/УВД/авиакомпания) ->
 * {@link MessageType#GROUND}.
 */
@Component
@Slf4j
public class AftnParser implements MessageParser {

    private static final Pattern PRIORITY_LINE = Pattern.compile(
            "^(FF|GG|KK|SS|DD)\\s+([A-Z0-9 ]{8,})$", Pattern.MULTILINE);
    private static final Pattern FILING_LINE = Pattern.compile(
            "^(\\d{6})\\s+([A-Z0-9]{4,8})$", Pattern.MULTILINE);
    private static final Pattern SERIAL_PATTERN = Pattern.compile("\\bSER[/ ]([A-Z0-9-]{1,16})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AN_PATTERN = Pattern.compile("\\bAN[/ ]([A-Z0-9-]{3,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FI_PATTERN = Pattern.compile("\\bFI[/ ]([A-Z0-9]{2,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MSGTYPE_PATTERN = Pattern.compile("^(MVT|LDM|DIV|DLA|AFTN_FREE)\\b", Pattern.MULTILINE);

    @Override
    public RawMessageFormat supportedFormat() {
        return RawMessageFormat.AFTN;
    }

    @Override
    public ParsedMessage parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new MessageParsingException(RawMessageFormat.AFTN, "AFTN: пустое сообщение");
        }

        String normalized = rawMessage.strip().replace("\r\n", "\n");

        Matcher priorityMatcher = PRIORITY_LINE.matcher(normalized);
        if (!priorityMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.AFTN,
                    "AFTN: не найдена строка priority indicator/адресов (FF|GG|KK|SS|DD <адреса>)");
        }
        String priority = priorityMatcher.group(1);
        String addressees = priorityMatcher.group(2).strip();

        Matcher filingMatcher = FILING_LINE.matcher(normalized);
        if (!filingMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.AFTN,
                    "AFTN: не найдена строка filing time/origin (<DDHHMM> <origin>)");
        }
        String filingTime = filingMatcher.group(1);
        String origin = filingMatcher.group(2);

        Matcher anMatcher = AN_PATTERN.matcher(normalized);
        String aircraftId = anMatcher.find() ? normalizeTail(anMatcher.group(1)) : null;

        Matcher fiMatcher = FI_PATTERN.matcher(normalized);
        String flightNumber = fiMatcher.find() ? fiMatcher.group(1).toUpperCase() : null;

        if (aircraftId == null && flightNumber == null) {
            throw new MessageParsingException(RawMessageFormat.AFTN,
                    "AFTN: телеграмма не несёт ни AN (борт), ни FI (рейс) — привязка к ВС невозможна");
        }

        Matcher serialMatcher = SERIAL_PATTERN.matcher(normalized);
        String externalMessageId = serialMatcher.find() ? serialMatcher.group(1) : null;

        Matcher msgTypeMatcher = MSGTYPE_PATTERN.matcher(normalized);
        String templateName = msgTypeMatcher.find() ? msgTypeMatcher.group(1).toUpperCase() : "AFTN_FREE_TEXT";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("priority", priority);
        metadata.put("addressees", addressees);
        metadata.put("origin", origin);
        metadata.put("filingTime", filingTime);

        String payload = extractPayload(normalized);

        return new ParsedMessage(MessageType.GROUND, templateName, aircraftId, flightNumber,
                payload, externalMessageId, metadata);
    }

    private String normalizeTail(String tail) {
        String upper = tail.toUpperCase();
        if (!upper.contains("-") && upper.length() >= 5) {
            return upper.substring(0, 2) + "-" + upper.substring(2);
        }
        return upper;
    }

    private String extractPayload(String normalized) {
        String[] lines = normalized.split("\n");
        StringBuilder payload = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (PRIORITY_LINE.matcher(trimmed).matches()) continue;
            if (FILING_LINE.matcher(trimmed).matches()) continue;
            if (SERIAL_PATTERN.matcher(trimmed).find() && trimmed.length() < 24) continue;
            if (AN_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) continue;
            if (FI_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) continue;
            if (payload.length() > 0) payload.append(' ');
            payload.append(trimmed);
        }
        return payload.toString();
    }
}
