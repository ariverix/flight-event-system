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

@DisplayName("Arinc618Parser — ARINC 618 air/ground (downlink/uplink)")
class Arinc618ParserTest {

    private final Arinc618Parser parser = new Arinc618Parser();

    @Test
    @DisplayName("supportedFormat() == ARINC_618")
    void supportsArinc618() {
        assertThat(parser.supportedFormat()).isEqualTo(RawMessageFormat.ARINC_618);
    }

    @Nested
    @DisplayName("Позитив: реальные примеры")
    class ValidExamples {

        @Test
        @DisplayName("OOOI-репорт (OUT/OFF) с явным LABEL — извлекает борт/рейс/тип/flightStage")
        void parsesOooiReport() {
            String raw = "AN/VP-BQR FI/SU1234 LABEL/Q0 OUT/1245 OFF/1252 MSGREF/618-AAA-001";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.flightNumber()).isEqualTo("SU1234");
            assertThat(result.messageType()).isEqualTo(MessageType.DOWNLINK);
            assertThat(result.templateName()).isEqualTo("Q0");
            assertThat(result.externalMessageId()).isEqualTo("618-AAA-001");
            assertThat(result.metadata())
                    .containsEntry("outTime", "1245")
                    .containsEntry("offTime", "1252")
                    .containsEntry("flightStage", "OFF");
        }

        @Test
        @DisplayName("OOOI-репорт без LABEL — templateName выводится как OOOI")
        void parsesOooiWithoutLabel() {
            String raw = "AN/VP-BQR FI/SU1234 OUT/0930";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.templateName()).isEqualTo("OOOI");
            assertThat(result.metadata()).containsEntry("outTime", "0930").containsEntry("flightStage", "OUT");
        }

        @Test
        @DisplayName("position report — извлекает координаты в metadata с positionSource=ACARS")
        void parsesPositionReport() {
            String raw = "AN/VP-BQR FI/SU1234 LAT/55.7558 LON/37.6173";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.templateName()).isEqualTo("POSITION_REPORT");
            assertThat(result.metadata())
                    .containsEntry("latitude", 55.7558)
                    .containsEntry("longitude", 37.6173)
                    .containsEntry("positionSource", "ACARS");
        }

        @Test
        @DisplayName("free text сообщение без OOOI/позиции/label — templateName=FREE_TEXT, payload сохранён")
        void parsesFreeText() {
            String raw = "AN/VP-BQR FI/SU1234 ENGINE OIL PRESSURE LOW ADVISORY";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.templateName()).isEqualTo("FREE_TEXT");
            assertThat(result.payload()).contains("ENGINE OIL PRESSURE LOW ADVISORY");
        }

        @Test
        @DisplayName("DIR/UPLINK переключает направление на UPLINK")
        void respectsExplicitUplinkDirection() {
            String raw = "AN/VP-BQR FI/SU1234 DIR/UPLINK LABEL/H1 CLEARANCE TEXT HERE";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.messageType()).isEqualTo(MessageType.UPLINK);
        }

        @Test
        @DisplayName("борт без дефиса (VPBQR) нормализуется в каноничный вид VP-BQR")
        void normalizesTailWithoutHyphen() {
            String raw = "AN/VPBQR FI/SU1234 LABEL/H1";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
        }
    }

    @Nested
    @DisplayName("Негатив: битые/неполные сообщения")
    class InvalidExamples {

        @Test
        @DisplayName("пустое сообщение -> MessageParsingException, не краш")
        void emptyMessageThrows() {
            assertThatThrownBy(() -> parser.parse(""))
                    .isInstanceOf(MessageParsingException.class)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null сообщение -> MessageParsingException")
        void nullMessageThrows() {
            assertThatThrownBy(() -> parser.parse(null))
                    .isInstanceOf(MessageParsingException.class);
        }

        @Test
        @DisplayName("без AN (борта) -> MessageParsingException с понятным сообщением")
        void missingAircraftIdThrows() {
            assertThatThrownBy(() -> parser.parse("FI/SU1234 LABEL/H1 SOME TEXT"))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("AN");
        }

        @Test
        @DisplayName("исключение несёт формат ARINC_618 для последующей маршрутизации в DLQ (P2-6)")
        void exceptionCarriesFormat() {
            MessageParsingException ex = (MessageParsingException) catchException(() -> parser.parse(""));
            assertThat(ex.getFormat()).isEqualTo(RawMessageFormat.ARINC_618);
        }

        private Throwable catchException(Runnable runnable) {
            try {
                runnable.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        }
    }
}
