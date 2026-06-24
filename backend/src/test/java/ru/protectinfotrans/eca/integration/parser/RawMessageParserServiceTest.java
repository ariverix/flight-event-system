package ru.protectinfotrans.eca.integration.parser;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.integration.parser.format.AftnParser;
import ru.protectinfotrans.eca.integration.parser.format.Arinc618Parser;
import ru.protectinfotrans.eca.integration.parser.format.Arinc620Parser;
import ru.protectinfotrans.eca.integration.parser.format.TypeBParser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RawMessageParserService — диспетчеризация по явному формату (P2-2)")
class RawMessageParserServiceTest {

    private final RawMessageParserService service = new RawMessageParserService(List.of(
            new Arinc618Parser(),
            new Arinc620Parser(new Arinc618Parser()),
            new TypeBParser(),
            new AftnParser()
    ), new SimpleMeterRegistry());

    @Nested
    @DisplayName("Диспетчеризация по каждому из 4 форматов")
    class Dispatch {

        @Test
        @DisplayName("ARINC_618 маршрутизируется в Arinc618Parser")
        void dispatchesArinc618() {
            ParsedMessage result = service.parse(RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.messageType()).isEqualTo(MessageType.DOWNLINK);
        }

        @Test
        @DisplayName("ARINC_620 маршрутизируется в Arinc620Parser")
        void dispatchesArinc620() {
            ParsedMessage result = service.parse(RawMessageFormat.ARINC_620,
                    "ORIG/AAA DEST/BBB\nAN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
        }

        @Test
        @DisplayName("TYPE_B маршрутизируется в TypeBParser")
        void dispatchesTypeB() {
            String raw = """
                    QU MOWSUXH
                    .MOWOPSU 191045
                    AN/VP-BQR
                    TEXT""";

            ParsedMessage result = service.parse(RawMessageFormat.TYPE_B, raw);

            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
        }

        @Test
        @DisplayName("AFTN маршрутизируется в AftnParser")
        void dispatchesAftn() {
            String raw = """
                    GG UUEEZRZX
                    191045 UUWWZTZX
                    AN/VP-BQR
                    TEXT""";

            ParsedMessage result = service.parse(RawMessageFormat.AFTN, raw);

            assertThat(result.aircraftId()).isEqualTo("VP-BQR");
            assertThat(result.messageType()).isEqualTo(MessageType.GROUND);
        }
    }

    @Nested
    @DisplayName("Ошибки парсинга не теряются молча")
    class ErrorHandling {

        @Test
        @DisplayName("MessageParsingException конкретного парсера пробрасывается наружу как есть (не маскируется)")
        void propagatesParsingExceptionUnchanged() {
            assertThatThrownBy(() -> service.parse(RawMessageFormat.ARINC_618, "GARBAGE WITHOUT FIELDS"))
                    .isInstanceOf(MessageParsingException.class);
        }

        @Test
        @DisplayName("формат без зарегистрированного парсера -> IllegalStateException (ошибка конфигурации)")
        void missingParserForFormatThrows() {
            RawMessageParserService emptyService = new RawMessageParserService(List.of(), new SimpleMeterRegistry());

            assertThatThrownBy(() -> emptyService.parse(RawMessageFormat.AFTN, "ANYTHING"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
