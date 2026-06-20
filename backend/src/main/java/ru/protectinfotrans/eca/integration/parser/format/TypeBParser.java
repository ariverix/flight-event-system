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
 * Type B — классическая авиационная телеграмма (SITA/ARINC teletype-style messaging,
 * предшественник современного ACARS, до сих пор используется для ground-ground обмена
 * между авиакомпаниями/handling-агентами/АДП).
 *
 * <p>Реальная структура Type B телеграммы:
 * <pre>
 * QU MOWSUXH
 * .MOWOPSU 191045
 * MSGTYPE/MVT
 * AN/VP-BQR
 * FI/SU1234
 * ARR AA1234/19 MOWLED AA1230 AA1238 0
 * </pre>
 * где:
 * <ul>
 *   <li>1-я строка — приоритет (2 буквы: {@code QU} обычный, {@code QX} срочный и т.п.)
 *       + адрес(а) получателя (7-буквенный SITA-style код, может повторяться несколько раз
 *       в строке, разделённые пробелом);</li>
 *   <li>2-я строка, начинающаяся с {@code .} — origin (7-символьный код станции-отправителя)
 *       + дата/время подачи (день+время, DDHHMM);</li>
 *   <li>{@code MSGTYPE/}, {@code AN/}, {@code FI/} — служебные поля сообщения (тип/борт/рейс),
 *       такая же конвенция полей, как в ARINC 618, заданная намеренно — Type B исторически
 *       переносит те же MVT/LDM/прочие авиационные телеграммы;</li>
 *   <li>остаток — свободный текст телеграммы (payload).</li>
 * </ul>
 * Type B не несёт направления борт/земля — это всегда обмен между наземными системами
 * → {@link MessageType#GROUND}.
 */
@Component
@Slf4j
public class TypeBParser implements MessageParser {

    private static final Pattern PRIORITY_LINE = Pattern.compile(
            "^(QU|QX|QK|QN|QS)\\s+([A-Z0-9 ]{7,})$", Pattern.MULTILINE);
    private static final Pattern ORIGIN_LINE = Pattern.compile(
            "^\\.([A-Z]{3,7})\\s+(\\d{6})$", Pattern.MULTILINE);
    private static final Pattern MSGTYPE_PATTERN = Pattern.compile("\\bMSGTYPE[/ ]([A-Z0-9]{2,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AN_PATTERN = Pattern.compile("\\bAN[/ ]([A-Z0-9-]{3,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FI_PATTERN = Pattern.compile("\\bFI[/ ]([A-Z0-9]{2,8})\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public RawMessageFormat supportedFormat() {
        return RawMessageFormat.TYPE_B;
    }

    @Override
    public ParsedMessage parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new MessageParsingException(RawMessageFormat.TYPE_B, "Type B: пустое сообщение");
        }

        String normalized = rawMessage.strip().replace("\r\n", "\n");

        Matcher priorityMatcher = PRIORITY_LINE.matcher(normalized);
        if (!priorityMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.TYPE_B,
                    "Type B: не найдена строка приоритета/адресов (QU|QX|QK|QN|QS <адреса>)");
        }
        String priority = priorityMatcher.group(1);
        String addressees = priorityMatcher.group(2).strip();

        Matcher originMatcher = ORIGIN_LINE.matcher(normalized);
        if (!originMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.TYPE_B,
                    "Type B: не найдена строка origin (.<код станции> <DDHHMM>)");
        }
        String origin = originMatcher.group(1);
        String filingTime = originMatcher.group(2);

        Matcher anMatcher = AN_PATTERN.matcher(normalized);
        String aircraftId = anMatcher.find() ? normalizeTail(anMatcher.group(1)) : null;

        Matcher fiMatcher = FI_PATTERN.matcher(normalized);
        String flightNumber = fiMatcher.find() ? fiMatcher.group(1).toUpperCase() : null;

        if (aircraftId == null && flightNumber == null) {
            throw new MessageParsingException(RawMessageFormat.TYPE_B,
                    "Type B: телеграмма не несёт ни AN (борт), ни FI (рейс) — привязка к ВС невозможна");
        }

        Matcher msgTypeMatcher = MSGTYPE_PATTERN.matcher(normalized);
        String templateName = msgTypeMatcher.find() ? msgTypeMatcher.group(1).toUpperCase() : "TYPE_B_TELEX";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("priority", priority);
        metadata.put("addressees", addressees);
        metadata.put("origin", origin);
        metadata.put("filingTime", filingTime);

        String payload = extractPayload(normalized);

        return new ParsedMessage(MessageType.GROUND, templateName, aircraftId, flightNumber,
                payload, null, metadata);
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
            if (ORIGIN_LINE.matcher(trimmed).matches()) continue;
            if (MSGTYPE_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) continue;
            if (AN_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) continue;
            if (FI_PATTERN.matcher(trimmed).find() && trimmed.length() < 20) continue;
            if (payload.length() > 0) payload.append(' ');
            payload.append(trimmed);
        }
        return payload.toString();
    }
}
