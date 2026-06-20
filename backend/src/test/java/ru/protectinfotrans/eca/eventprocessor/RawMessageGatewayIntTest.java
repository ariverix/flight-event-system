package ru.protectinfotrans.eca.eventprocessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.integration.adapter.in.RawIncomingMessageRequest;
import ru.protectinfotrans.eca.integration.adapter.in.RawMessageController;
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-2: интеграционный (реальный Postgres, реальный диспетчер парсеров) сквозной путь
 * сырого сообщения «борт-земля» (ARINC 618/620, Type B, AFTN) -> {@code RawMessageController
 * #receiveRawMessage} (модуль {@code integration}) -> {@code RawMessageParserService}
 * (диспетчеризация по формату) -> {@code MessageInputPort} (входной порт {@code eventprocessor}) ->
 * ТОТ ЖЕ конвейер приёма, что и структурированный путь (P2-1: persist в {@code messages},
 * идемпотентность по {@code externalMessageId}, публикация {@code NormalizedEvent} через
 * Transactional Outbox — реакция Execution Engine).
 *
 * <p>Дополняет unit-тесты парсеров ({@code integration.parser.format.*Test}, изолированный
 * разбор без Spring) и unit-тест {@code RawMessageControllerTest} (мокнутые зависимости) —
 * здесь весь путь end-to-end с реальной схемой БД (Flyway V1-V25).
 */
@DisplayName("P2-2: сквозной приём сырых сообщений (ARINC 618/620, Type B, AFTN)")
class RawMessageGatewayIntTest extends BaseIntegrationTest {

    @Autowired
    private RawMessageController rawMessageController;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    @Nested
    @DisplayName("ARINC 618 (air/ground downlink)")
    class Arinc618EndToEnd {

        @Test
        @DisplayName("OOOI-репорт с MSGREF -> persist + flightStage в metadata + ровно одна публикация события")
        void parsesAndPersistsOooiReport() {
            String raw = "AN/VP-BQR FI/SU1234 LABEL/Q0 OUT/1245 OFF/1252 MSGREF/618-INT-001";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(RawMessageFormat.ARINC_618, raw);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().messageId()).isNotNull();

            Long messageCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ? "
                            + "AND aircraft_id = ? AND flight_number = ?",
                    Long.class, "618-INT-001", AIRCRAFT_ID, FLIGHT_NUMBER);
            assertThat(messageCount).isEqualTo(1L);

            String metadataJson = jdbcTemplate.queryForObject(
                    "SELECT metadata_json FROM messages WHERE external_message_id = ?",
                    String.class, "618-INT-001");
            assertThat(metadataJson).contains("flightStage").contains("OFF");

            Long publicationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%NormalizedEvent%' "
                            + "AND serialized_event LIKE ?",
                    Long.class, "%\"messageId\":" + response.getBody().messageId() + "%");
            assertThat(publicationCount)
                    .as("распарсенное сообщение должно дойти до движка через тот же Outbox-механизм, что и P2-1")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("повторная доставка с тем же MSGREF (externalMessageId) -> идемпотентность P2-1 не нарушена парсером")
        void duplicateRawDeliveryIsDeduplicated() {
            String raw = "AN/VP-BQR FI/SU1234 LABEL/H1 ENGINE ADVISORY MSGREF/618-INT-DUP";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(RawMessageFormat.ARINC_618, raw);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> first =
                    rawMessageController.receiveRawMessage(request);
            ResponseEntity<RawMessageController.RawMessageReceivedResponse> second =
                    rawMessageController.receiveRawMessage(request);

            assertThat(second.getBody().messageId())
                    .as("парсер извлёк один и тот же externalMessageId из MSGREF -> дедуп шлюза P2-1 срабатывает")
                    .isEqualTo(first.getBody().messageId());

            Long messageCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ?",
                    Long.class, "618-INT-DUP");
            assertThat(messageCount).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("AFTN")
    class AftnEndToEnd {

        @Test
        @DisplayName("MVT-телеграмма AFTN с SER -> persist GROUND-сообщения с борт/рейс/payload")
        void parsesAndPersistsAftnMovementTelegram() {
            String raw = """
                    GG UUEEZRZX UUWWZDZX
                    191045 UUWWZTZX
                    SER/AFTN-INT-0451
                    AN/VP-BQR
                    FI/SU1234
                    MVT AA1234/19 UUEE AA1230 AA1238 0""";
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(RawMessageFormat.AFTN, raw);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            Long messageCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ? "
                            + "AND aircraft_id = ? AND flight_number = ? AND message_type = 'GROUND'",
                    Long.class, "AFTN-INT-0451", AIRCRAFT_ID, FLIGHT_NUMBER);
            assertThat(messageCount).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Ошибка парсинга — без потерь, без 500")
    class ParsingFailureHandling {

        @Test
        @DisplayName("битое ARINC 618 сообщение -> MessageParsingException наружу, ничего не сохранено в messages")
        void brokenMessageDoesNotPersistAnything() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "GARBAGE WITHOUT ANY SERVICE FIELDS");

            assertThatThrownBy(() -> rawMessageController.receiveRawMessage(request))
                    .isInstanceOf(MessageParsingException.class)
                    .isInstanceOf(IllegalArgumentException.class);

            Long messageCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages", Long.class);
            assertThat(messageCount)
                    .as("ошибка парсинга не должна создавать частично заполненную запись (задел DLQ — P2-6)")
                    .isEqualTo(0L);
        }
    }
}
