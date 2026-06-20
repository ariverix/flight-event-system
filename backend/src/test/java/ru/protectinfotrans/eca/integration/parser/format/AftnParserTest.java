package ru.protectinfotrans.eca.integration.parser.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AftnParser — AFTN телеграмма (ICAO Annex 10)")
class AftnParserTest {

    private final AftnParser parser = new AftnParser();

    @Test
    @DisplayName("supportedFormat() == AFTN")
    void supportsAftn() {
        assertThat(parser.supportedFormat()).isEqualTo(RawMessageFormat.AFTN);
    }

    @Nested
    @DisplayName("Позитив: реальные примеры")
    class ValidExamples {

        @Test
        @DisplayName("MVT-телеграмма AFTN с приоритетом GG, AN/FI, SER -> полное извлечение полей")
        void parsesMovementTelegramWithSerial() {
            String raw = """
                    GG UUEEZRZX UUWWZDZX
                    191045 UUWWZTZX
                    SER/AFTN-2026-0451
                    AN/VP-BQR
                    FI/SU1234
                    MVT AA1234/19 UUEE AA1230 AA1238 0""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.flightNumber()).isEqualTo("SU1234");
            assertThat(result.templateName()).isEqualTo("MVT");
            assertThat(result.externalMessageId()).isEqualTo("AFTN-2026-0451");
            assertThat(result.metadata())
                    .containsEntry("priority", "GG")
                    .containsEntry("origin", "UUWWZTZX")
                    .containsEntry("filingTime", "191045");
            assertThat((String) result.metadata().get("addressees")).contains("UUEEZRZX").contains("UUWWZDZX");
            assertThat(result.payload()).contains("MVT AA1234/19 UUEE AA1230 AA1238 0");
        }

        @Test
        @DisplayName("без SER (serial number) -> externalMessageId=null, остальное распознано")
        void parsesWithoutSerialNumber() {
            String raw = """
                    FF UUEEZRZX
                    191045 UUWWZTZX
                    AN/VP-BQR
                    FI/SU1234
                    DLA ADVISORY TEXT""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.externalMessageId()).isNull();
            assertThat(result.templateName()).isEqualTo("DLA");
        }

        @Test
        @DisplayName("приоритет KK (срочный) распознаётся корректно")
        void recognizesUrgentPriority() {
            String raw = """
                    KK UUEEZRZX
                    191045 UUWWZTZX
                    AN/VP-BQR
                    URGENT TEXT""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.metadata()).containsEntry("priority", "KK");
        }
    }

    @Nested
    @DisplayName("Негатив: битые/неполные сообщения")
    class InvalidExamples {

        @Test
        @DisplayName("без строки приоритета -> MessageParsingException")
        void missingPriorityLineThrows() {
            String raw = """
                    191045 UUWWZTZX
                    AN/VP-BQR
                    TEXT""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("priority");
        }

        @Test
        @DisplayName("без строки filing time/origin -> MessageParsingException")
        void missingFilingLineThrows() {
            String raw = """
                    GG UUEEZRZX
                    AN/VP-BQR
                    TEXT""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("filing");
        }

        @Test
        @DisplayName("без AN и без FI -> MessageParsingException")
        void missingIdentificationThrows() {
            String raw = """
                    GG UUEEZRZX
                    191045 UUWWZTZX
                    TEXT WITHOUT IDENTIFICATION""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("привязка к ВС невозможна");
        }

        @Test
        @DisplayName("null сообщение -> MessageParsingException")
        void nullMessageThrows() {
            assertThatThrownBy(() -> parser.parse(null))
                    .isInstanceOf(MessageParsingException.class);
        }
    }
}
