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

@DisplayName("TypeBParser — классическая авиационная телеграмма Type B")
class TypeBParserTest {

    private final TypeBParser parser = new TypeBParser();

    @Test
    @DisplayName("supportedFormat() == TYPE_B")
    void supportsTypeB() {
        assertThat(parser.supportedFormat()).isEqualTo(RawMessageFormat.TYPE_B);
    }

    @Nested
    @DisplayName("Позитив: реальные примеры")
    class ValidExamples {

        @Test
        @DisplayName("MVT-телеграмма с AN/FI — извлекает адресатов, origin, борт, рейс, payload")
        void parsesMovementTelegram() {
            String raw = """
                    QU MOWSUXH MOWOPXH
                    .MOWOPSU 191045
                    MSGTYPE/MVT
                    AN/VP-BQR
                    FI/SU1234
                    ARR AA1234/19 MOWLED AA1230 AA1238 0""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.flightNumber()).isEqualTo("SU1234");
            assertThat(result.templateName()).isEqualTo("MVT");
            assertThat(result.metadata())
                    .containsEntry("priority", "QU")
                    .containsEntry("origin", "MOWOPSU")
                    .containsEntry("filingTime", "191045");
            assertThat((String) result.metadata().get("addressees")).contains("MOWSUXH").contains("MOWOPXH");
            assertThat(result.payload()).contains("ARR AA1234/19 MOWLED AA1230 AA1238 0");
        }

        @Test
        @DisplayName("телеграмма без MSGTYPE -> дефолтный TYPE_B_TELEX")
        void defaultsTemplateNameWhenMissing() {
            String raw = """
                    QU MOWSUXH
                    .MOWOPSU 191045
                    AN/VP-BQR
                    SOME FREE TEXT CONTENT""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.templateName()).isEqualTo("TYPE_B_TELEX");
        }

        @Test
        @DisplayName("телеграмма только с FI (без AN) — допустимо, привязка по рейсу")
        void allowsFlightIdOnlyWithoutTail() {
            String raw = """
                    QU MOWSUXH
                    .MOWOPSU 191045
                    FI/SU1234
                    DELAY ADVISORY TEXT""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.aircraftId()).isNull();
            assertThat(result.flightNumber()).isEqualTo("SU1234");
        }
    }

    @Nested
    @DisplayName("Негатив: битые/неполные сообщения")
    class InvalidExamples {

        @Test
        @DisplayName("без строки приоритета/адресов -> MessageParsingException")
        void missingPriorityLineThrows() {
            String raw = """
                    .MOWOPSU 191045
                    AN/VP-BQR
                    TEXT""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("приоритета");
        }

        @Test
        @DisplayName("без строки origin -> MessageParsingException")
        void missingOriginLineThrows() {
            String raw = """
                    QU MOWSUXH
                    AN/VP-BQR
                    TEXT""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("origin");
        }

        @Test
        @DisplayName("без AN и без FI -> MessageParsingException (нет привязки к ВС)")
        void missingBothTailAndFlightThrows() {
            String raw = """
                    QU MOWSUXH
                    .MOWOPSU 191045
                    SOME TEXT WITHOUT IDENTIFICATION""";

            assertThatThrownBy(() -> parser.parse(raw))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("привязка к ВС невозможна");
        }

        @Test
        @DisplayName("пустое сообщение -> MessageParsingException")
        void emptyMessageThrows() {
            assertThatThrownBy(() -> parser.parse(""))
                    .isInstanceOf(MessageParsingException.class);
        }
    }
}
