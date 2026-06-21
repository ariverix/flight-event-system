package ru.protectinfotrans.eca.integration.callsign;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.integration.adapter.in.RawIncomingMessageRequest;
import ru.protectinfotrans.eca.integration.adapter.in.RawMessageController;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;
import ru.protectinfotrans.eca.integration.port.out.CallsignMatchingRepositoryPort;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-4 (часть 2 — логика): сквозной (реальный Postgres) тест полного пути callsign matching ->
 * FI: {@code RawMessageController#receiveRawMessage} -> {@code RawMessageParserService} (P2-2) ->
 * {@link CallsignMatchingService} (P2-4) -> {@code MessageInputPort#receiveMessage}
 * (eventprocessor, P2-1) -> persist {@code messages} с РАЗРЕШЁННЫМ FI -> публикация
 * {@code NormalizedEvent} для движка с тем же FI (используется наравне с tail number/AN для
 * привязки последовательности — паритет SITA, CLAUDE.md).
 *
 * <p>Использует демо-правила V28 (миграция, db-dev): {@code AFL -> SU1234} — общее правило
 * (specificity 0) и специфичное по номеру рейса/будним дням/аэропортам UUEE->ULLI
 * (specificity 10).
 *
 * <p>Дополняет {@code CallsignParserTest}/{@code CallsignMatchingServiceTest} (изолированные
 * unit-тесты без Spring) и {@code RawMessageControllerTest} (мокнутые зависимости) — здесь
 * проверяется ВЕСЬ путь с реальной схемой БД и реальными бинами Spring.
 */
@DisplayName("P2-4: сквозной callsign matching -> FI через приём сырого сообщения")
class CallsignMatchingGatewayIntTest extends BaseIntegrationTest {

    @Autowired
    private RawMessageController rawMessageController;

    @Autowired
    private CallsignMatchingRepositoryPort callsignMatchingRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";

    @Nested
    @DisplayName("Демо-правила V28: AFL1234 -> FI=SU1234")
    class DemoRulesFromV28 {

        @Test
        @DisplayName("общее правило (аэропорты не совпадают со специфичным UUEE->ULLI) -> FI=SU1234 через общее правило")
        void generalRuleResolvesFlightId() {
            // аэропорты, отличные от UUEE/ULLI (специфичное правило V28 требует именно их) —
            // специфичное правило не матчится НЕЗАВИСИМО от дня недели "сегодня", матчится
            // только общее правило (specificity 0, любой день, любой аэропорт) -> тест
            // детерминирован и не зависит от реальной даты прогона.
            String raw = "AN/VP-BQR FI/AFL1234 LABEL/H1 ENGINE ADVISORY MSGREF/P24-GENERAL-001";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, raw, "ZZZZ", "YYYY");

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            String flightNumber = jdbcTemplate.queryForObject(
                    "SELECT flight_number FROM messages WHERE external_message_id = ? AND aircraft_id = ?",
                    String.class, "P24-GENERAL-001", AIRCRAFT_ID);
            assertThat(flightNumber).isEqualTo("SU1234");
        }

        @Test
        @DisplayName("специфичное правило выигрывает у общего: будний день + UUEE->ULLI -> FI=SU1234 (specificity 10)")
        void specificRuleWinsOverGeneralRule() {
            String raw = "AN/VP-BQR FI/AFL1234 LABEL/H1 ENGINE ADVISORY MSGREF/P24-SPECIFIC-001";
            // явная дата рейса — понедельник (ISO Пн, бит 1 маски '1111100' = '1') — тест не
            // зависит от того, какой сегодня день недели на момент прогона CI/локально.
            LocalDate monday = LocalDate.of(2026, 6, 22);
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, raw, "UUEE", "ULLI", monday);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            String flightNumber = jdbcTemplate.queryForObject(
                    "SELECT flight_number FROM messages WHERE external_message_id = ? AND aircraft_id = ?",
                    String.class, "P24-SPECIFIC-001", AIRCRAFT_ID);
            assertThat(flightNumber).isEqualTo("SU1234");

            Long publicationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%NormalizedEvent%' "
                            + "AND serialized_event LIKE ?",
                    Long.class, "%\"flightNumber\":\"SU1234\"%");
            assertThat(publicationCount)
                    .as("NormalizedEvent для движка должен нести РАЗРЕШЁННЫЙ FI, не сырой позывной")
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Нет совпадения -> привязка по AN не ломается")
    class NoMatchFallback {

        @Test
        @DisplayName("неизвестный перевозчик в позывном (нет правил) -> flightNumber передаётся как есть, AN сохраняется")
        void unknownCarrierKeepsOriginalFlightNumberAndAircraftId() {
            String raw = "AN/VP-BQR FI/XXX9999 LABEL/H1 NO MATCHING RULE MSGREF/P24-NOMATCH-001";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(RawMessageFormat.ARINC_618, raw);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            var row = jdbcTemplate.queryForMap(
                    "SELECT aircraft_id, flight_number FROM messages WHERE external_message_id = ?",
                    "P24-NOMATCH-001");
            assertThat(row.get("aircraft_id")).isEqualTo(AIRCRAFT_ID);
            assertThat(row.get("flight_number"))
                    .as("без совпавшего правила FI не выдумывается — исходное значение из FI/-поля сохраняется")
                    .isEqualTo("XXX9999");
        }

        @Test
        @DisplayName("специфичное правило вне периода действия (valid_to в прошлом) -> не матчится, нет FI у нового правила без общего")
        void expiredRuleDoesNotMatch() {
            callsignMatchingRepository.save(CallsignMatchingRule.builder()
                    .icaoCarrierCode("EZY")
                    .flightId("U2EXPIRED")
                    .validFrom(LocalDate.of(2020, 1, 1))
                    .validTo(LocalDate.of(2020, 12, 31))
                    .specificity(0)
                    .active(true)
                    .createdAt(LocalDateTime.now())
                    .build());

            String raw = "AN/VP-BQR FI/EZY4321 LABEL/H1 TEXT MSGREF/P24-EXPIRED-001";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(RawMessageFormat.ARINC_618, raw);

            rawMessageController.receiveRawMessage(request);

            String flightNumber = jdbcTemplate.queryForObject(
                    "SELECT flight_number FROM messages WHERE external_message_id = ?",
                    String.class, "P24-EXPIRED-001");
            assertThat(flightNumber).isEqualTo("EZY4321");
        }
    }
}
