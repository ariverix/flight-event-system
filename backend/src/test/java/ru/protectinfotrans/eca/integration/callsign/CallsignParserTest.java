package ru.protectinfotrans.eca.integration.callsign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CallsignParser — разбор позывного на код перевозчика + номер рейса (P2-4, часть 2)")
class CallsignParserTest {

    private final CallsignParser parser = new CallsignParser();

    @Nested
    @DisplayName("Позитив: ICAO-позывной (3 буквы) — основной случай")
    class IcaoCallsigns {

        @Test
        @DisplayName("AFL1234 -> carrier=AFL, number=1234 (демо-сценарий V28)")
        void parsesDemoCallsign() {
            Optional<ParsedCallsign> result = parser.parse("AFL1234");

            assertThat(result).isPresent();
            assertThat(result.get().icaoCarrierCode()).isEqualTo("AFL");
            assertThat(result.get().flightNumber()).isEqualTo("1234");
        }

        @Test
        @DisplayName("регистр не важен — приводится к верхнему")
        void normalizesCase() {
            Optional<ParsedCallsign> result = parser.parse("afl1234");

            assertThat(result).isPresent();
            assertThat(result.get().icaoCarrierCode()).isEqualTo("AFL");
            assertThat(result.get().flightNumber()).isEqualTo("1234");
        }

        @Test
        @DisplayName("пробел/дефис между кодом и номером игнорируется")
        void ignoresSeparatorBetweenCarrierAndNumber() {
            assertThat(parser.parse("AFL 1234").get().flightNumber()).isEqualTo("1234");
            assertThat(parser.parse("AFL-1234").get().flightNumber()).isEqualTo("1234");
        }

        @Test
        @DisplayName("номер рейса с буквенным суффиксом (BAW123A)")
        void parsesFlightNumberWithLetterSuffix() {
            Optional<ParsedCallsign> result = parser.parse("BAW123A");

            assertThat(result).isPresent();
            assertThat(result.get().icaoCarrierCode()).isEqualTo("BAW");
            assertThat(result.get().flightNumber()).isEqualTo("123A");
        }

        @Test
        @DisplayName("ведущие/хвостовые пробелы обрезаются")
        void stripsSurroundingWhitespace() {
            Optional<ParsedCallsign> result = parser.parse("  AFL1234  ");

            assertThat(result).isPresent();
            assertThat(result.get().icaoCarrierCode()).isEqualTo("AFL");
        }
    }

    @Nested
    @DisplayName("Позитив: IATA-позывной (2 символа) — вырожденный случай, тот же алгоритм")
    class IataCallsigns {

        @Test
        @DisplayName("SU1234 -> carrier=SU, number=1234 (IATA-код, 2 буквы)")
        void parsesTwoLetterIataCarrier() {
            Optional<ParsedCallsign> result = parser.parse("SU1234");

            assertThat(result).isPresent();
            assertThat(result.get().icaoCarrierCode()).isEqualTo("SU");
            assertThat(result.get().flightNumber()).isEqualTo("1234");
        }
    }

    @Nested
    @DisplayName("Негатив: не похоже на позывной -> Optional.empty(), без исключений")
    class InvalidCallsigns {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        @DisplayName("null/пустая/пробельная строка")
        void blankInputReturnsEmpty(String input) {
            assertThat(parser.parse(input)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "VP-BQR",      // tail number, не позывной рейса
                "1234",        // только цифры, без кода перевозчика
                "ABCDE1234",   // слишком длинный буквенный префикс (>3)
                "AFL",         // нет номера рейса
                "AFL12345",    // номер рейса длиннее 4 цифр
        })
        @DisplayName("структура не соответствует грамматике позывного")
        void malformedInputReturnsEmpty(String input) {
            assertThat(parser.parse(input)).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "AFL1234, AFL, 1234",
            "SVR5678, SVR, 5678",
            "DLH1, DLH, 1",
    })
    @DisplayName("табличный прогон разбора нескольких реальных позывных")
    void parsesVariousCallsigns(String callsign, String expectedCarrier, String expectedNumber) {
        Optional<ParsedCallsign> result = parser.parse(callsign);

        assertThat(result).isPresent();
        assertThat(result.get().icaoCarrierCode()).isEqualTo(expectedCarrier);
        assertThat(result.get().flightNumber()).isEqualTo(expectedNumber);
    }
}
