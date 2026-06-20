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
 * ARINC 618 — Air/Ground Character-Oriented Protocol, текстовые ACARS downlink/uplink
 * сообщения (борт <-> земля).
 *
 * <p>Реальный текстовый формат ACARS-телеграммы (классический "Telex"/teletype-style блок,
 * как реально приходит с борта и как реализован в большинстве наземных ACARS-гейтвеев,
 * включая SITA AIRCOM):
 * <pre>
 * .VPBQR1 SU1234
 * AN VP-BQR
 * FI SU1234
 * H1
 * OUT 1245 OFF 1252
 * </pre>
 * либо однострочный вариант с явными метками полей (формат, который реально шлют ACMS/FMS
 * как plain-text при отсутствии бинарного канала — также покрывается этим парсером):
 * <pre>
 * AN/VP-BQR FI/SU1234 LABEL/H1 OUT/1245 OFF/1252
 * </pre>
 *
 * <p>Ключевые поля ARINC 618, которые извлекаем:
 * <ul>
 *   <li><b>AN</b> — Aircraft tail number (регистрация ВС);</li>
 *   <li><b>FI</b> — Flight Id (рейс/идентификатор полёта);</li>
 *   <li><b>LABEL</b> (он же блок типа сообщения, 2 символа в реальном ARINC 618, например
 *       {@code H1} free text, {@code Q0} OOOI, {@code 5U} position) — тип сообщения;</li>
 *   <li>OOOI-метки ({@code OUT}/{@code OFF}/{@code ON}/{@code IN}) — событие смены стадии полёта;</li>
 *   <li>{@code POS}/{@code LAT}/{@code LON} — позиционный отчёт.</li>
 * </ul>
 * Направление (DOWNLINK с борта / UPLINK на борт) задаётся явно вызывающей стороной через
 * {@code DIR} (по умолчанию DOWNLINK — ARINC 618 в обратном направлении на практике
 * существенно реже встречается как сырой текст с этими метками; явный DIR не теряет
 * информацию, если она есть).
 */
@Component
@Slf4j
public class Arinc618Parser implements MessageParser {

    private static final Pattern AN_PATTERN = Pattern.compile("\\bAN[/ ]([A-Z0-9-]{3,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FI_PATTERN = Pattern.compile("\\bFI[/ ]([A-Z0-9]{2,8})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL_PATTERN = Pattern.compile("\\bLABEL[/ ]([A-Z0-9]{2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIR_PATTERN = Pattern.compile("\\bDIR[/ ](UPLINK|DOWNLINK)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MSG_REF_PATTERN = Pattern.compile("\\bMSGREF[/ ]([A-Z0-9.-]{1,32})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OOOI_PATTERN = Pattern.compile(
            "\\b(OUT|OFF|ON|IN)[/ ](\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POS_LAT_PATTERN = Pattern.compile(
            "\\bLAT[/ =]([+-]?\\d+(?:\\.\\d+)?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern POS_LON_PATTERN = Pattern.compile(
            "\\bLON[/ =]([+-]?\\d+(?:\\.\\d+)?)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public RawMessageFormat supportedFormat() {
        return RawMessageFormat.ARINC_618;
    }

    @Override
    public ParsedMessage parse(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            throw new MessageParsingException(RawMessageFormat.ARINC_618, "ARINC 618: пустое сообщение");
        }

        String normalized = rawMessage.strip();

        Matcher anMatcher = AN_PATTERN.matcher(normalized);
        if (!anMatcher.find()) {
            throw new MessageParsingException(RawMessageFormat.ARINC_618,
                    "ARINC 618: не найден обязательный идентификатор борта (AN/<tail>)");
        }
        String aircraftId = normalizeTail(anMatcher.group(1));

        Matcher fiMatcher = FI_PATTERN.matcher(normalized);
        String flightNumber = fiMatcher.find() ? fiMatcher.group(1).toUpperCase() : null;

        Matcher dirMatcher = DIR_PATTERN.matcher(normalized);
        MessageType messageType = dirMatcher.find()
                ? MessageType.valueOf(dirMatcher.group(1).toUpperCase())
                : MessageType.DOWNLINK;

        Matcher labelMatcher = LABEL_PATTERN.matcher(normalized);
        String label = labelMatcher.find() ? labelMatcher.group(1).toUpperCase() : null;

        Map<String, Object> metadata = new HashMap<>();
        String templateName;

        Matcher oooiMatcher = OOOI_PATTERN.matcher(normalized);
        boolean hasOooi = false;
        String lastStage = null;
        while (oooiMatcher.find()) {
            hasOooi = true;
            String stage = oooiMatcher.group(1).toUpperCase();
            String timeHHmm = oooiMatcher.group(2);
            metadata.put(stage.toLowerCase() + "Time", timeHHmm);
            lastStage = stage;
        }

        Matcher latMatcher = POS_LAT_PATTERN.matcher(normalized);
        Matcher lonMatcher = POS_LON_PATTERN.matcher(normalized);
        boolean hasPosition = latMatcher.find() && lonMatcher.find();
        if (hasPosition) {
            metadata.put("latitude", Double.parseDouble(latMatcher.group(1)));
            metadata.put("longitude", Double.parseDouble(lonMatcher.group(1)));
            metadata.put("positionSource", "ACARS");
        }

        // flightStage выводится из OOOI-меток НЕЗАВИСИМО от того, как определён templateName —
        // реальная телеграмма часто несёт явный LABEL (например Q0) И OOOI-метки одновременно
        // (см. реальный пример формата в javadoc класса), и flightStage критичен для критерия
        // "flight stage" движка (паритет SITA) даже когда LABEL присутствует.
        if (hasOooi && lastStage != null) {
            metadata.put("flightStage", mapOooiToFlightStage(lastStage));
        }

        if (label != null) {
            templateName = label;
        } else if (hasOooi) {
            templateName = "OOOI";
        } else if (hasPosition) {
            templateName = "POSITION_REPORT";
        } else {
            templateName = "FREE_TEXT";
        }

        Matcher msgRefMatcher = MSG_REF_PATTERN.matcher(normalized);
        String externalMessageId = msgRefMatcher.find() ? msgRefMatcher.group(1) : null;

        String payload = stripServiceFields(normalized);

        return new ParsedMessage(messageType, templateName, aircraftId, flightNumber, payload,
                externalMessageId, metadata.isEmpty() ? null : metadata);
    }

    private String normalizeTail(String tail) {
        String upper = tail.toUpperCase();
        // частый вариант реального борта без дефиса (VPBQR) -> канонический вид VP-BQR,
        // используемый везде в проекте (демо-сценарий VP-BQR/SU1234).
        if (!upper.contains("-") && upper.length() >= 5) {
            return upper.substring(0, 2) + "-" + upper.substring(2);
        }
        return upper;
    }

    private String mapOooiToFlightStage(String oooiStage) {
        return switch (oooiStage) {
            case "OUT" -> "OUT";
            case "OFF" -> "OFF";
            case "ON" -> "ON";
            case "IN" -> "IN";
            default -> null;
        };
    }

    private String stripServiceFields(String text) {
        String stripped = text
                .replaceAll(AN_PATTERN.pattern(), "")
                .replaceAll(FI_PATTERN.pattern(), "")
                .replaceAll(LABEL_PATTERN.pattern(), "")
                .replaceAll(DIR_PATTERN.pattern(), "")
                .replaceAll(MSG_REF_PATTERN.pattern(), "")
                .replaceAll("\\s+", " ")
                .strip();
        return stripped.isEmpty() ? text.strip() : stripped;
    }
}
