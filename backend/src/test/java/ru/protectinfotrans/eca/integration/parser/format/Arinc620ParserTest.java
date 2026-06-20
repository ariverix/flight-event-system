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

@DisplayName("Arinc620Parser — ARINC 620 ground/ground")
class Arinc620ParserTest {

    private final Arinc620Parser parser = new Arinc620Parser(new Arinc618Parser());

    @Test
    @DisplayName("supportedFormat() == ARINC_620")
    void supportsArinc620() {
        assertThat(parser.supportedFormat()).isEqualTo(RawMessageFormat.ARINC_620);
    }

    @Nested
    @DisplayName("Позитив: реальные примеры")
    class ValidExamples {

        @Test
        @DisplayName("пересылка OOOI-репорта между наземными узлами — GROUND, ORIG/DEST в metadata")
        void parsesGroundToGroundForward() {
            String raw = """
                    ORIG/DSP-MOW DEST/ZASHITAINFOTRANS
                    AN/VP-BQR FI/SU1234 LABEL/Q0
                    MSGREF/620-2026-000451
                    OUT/1245""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.flightNumber()).isEqualTo("SU1234");
            assertThat(result.externalMessageId()).isEqualTo("620-2026-000451");
            assertThat(result.metadata())
                    .containsEntry("groundOrig", "DSP-MOW")
                    .containsEntry("groundDest", "ZASHITAINFOTRANS")
                    .containsEntry("outTime", "1245");
        }

        @Test
        @DisplayName("направление всегда GROUND, даже если тело несёт DIR/UPLINK")
        void alwaysGroundRegardlessOfBodyDirection() {
            String raw = """
                    ORIG/AAA DEST/BBB
                    AN/VP-BQR FI/SU1234 DIR/UPLINK LABEL/H1 CLEARANCE""";

            ParsedMessage result = parser.parse(raw);

            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
        }
    }

    @Nested
    @DisplayName("Негатив: битые/неполные сообщения")
    class InvalidExamples {

        @Test
        @DisplayName("без ORIG/DEST -> MessageParsingException")
        void missingGroundHeaderThrows() {
            assertThatThrownBy(() -> parser.parse("AN/VP-BQR FI/SU1234 LABEL/Q0 OUT/1245"))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("ORIG");
        }

        @Test
        @DisplayName("ORIG/DEST есть, но тело битое (нет AN) -> MessageParsingException с причиной из тела")
        void brokenBodyThrows() {
            assertThatThrownBy(() -> parser.parse("ORIG/AAA DEST/BBB\nFI/SU1234 LABEL/Q0"))
                    .isInstanceOf(MessageParsingException.class)
                    .hasMessageContaining("тело сообщения не распознано");
        }

        @Test
        @DisplayName("пустое сообщение -> MessageParsingException")
        void emptyMessageThrows() {
            assertThatThrownBy(() -> parser.parse(""))
                    .isInstanceOf(MessageParsingException.class);
        }
    }
}
