package ru.protectinfotrans.eca.integration.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.integration.application.DeadLetterQueueService;
import ru.protectinfotrans.eca.integration.application.DeadLetterRequestContext;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;
import ru.protectinfotrans.eca.integration.parser.RawMessageIngestSupport;
import ru.protectinfotrans.eca.integration.parser.RawMessageParserService;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для {@link RawMessageController} (P2-2: приём сырых сообщений «борт-земля»,
 * P2-4: callsign matching через {@link RawMessageIngestSupport}, P2-6: DLQ при сбое).
 *
 * <p>Контроллер живёт в модуле {@code integration} (не {@code eventprocessor}) и вызывает
 * {@link MessageInputPort} (входной порт {@code eventprocessor}, named interface {@code port-in})
 * напрямую — без этого возник бы цикл границ модулей, см. javadoc {@link RawMessageController}.
 *
 * <p>P2-6: callsign matching (P2-4) теперь инкапсулирован в {@link RawMessageIngestSupport}
 * (общий шаг конвейера, переиспользуемый ручным reprocess DLQ-записи) — мокается напрямую,
 * без {@code CallsignMatchingService} на уровне контроллера.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RawMessageController")
class RawMessageControllerTest {

    @Mock
    private RawMessageParserService rawMessageParserService;

    @Mock
    private MessageInputPort messageInputPort;

    @Mock
    private RawMessageIngestSupport ingestSupport;

    @Mock
    private DeadLetterQueueService deadLetterQueueService;

    private RawMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new RawMessageController(rawMessageParserService, messageInputPort, ingestSupport,
                deadLetterQueueService);
    }

    @Nested
    @DisplayName("Приём сырых сообщений")
    class ReceiveRawMessage {

        @Test
        @DisplayName("должен разобрать сырое сообщение и передать структурированные поля в MessageInputPort")
        void shouldParseAndForwardToMessageInputPort() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", "618-REF-1",
                    Map.of("foo", "bar"));
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, request.rawMessage()))
                    .thenReturn(parsed);
            Map<String, Object> metadata = new HashMap<>(Map.of("foo", "bar", "externalMessageId", "618-REF-1"));
            when(ingestSupport.buildMetadata(parsed)).thenReturn(metadata);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), anyMap()))
                    .thenReturn(99L);

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    controller.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().messageId()).isEqualTo(99L);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(messageInputPort).receiveMessage(
                    eq(MessageType.DOWNLINK), eq("H1"), eq("VP-BQR"), eq("SU1234"), eq("TEXT"), captor.capture());
            assertThat(captor.getValue())
                    .containsEntry("foo", "bar")
                    .containsEntry("externalMessageId", "618-REF-1");
            verify(deadLetterQueueService, never()).captureFailure(any(), any(), any(), any());
        }

        @Test
        @DisplayName("без externalMessageId в распарсенном сообщении — metadata не получает это поле")
        void shouldNotAddExternalMessageIdWhenAbsent() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.TYPE_B, "RAW TYPE B TEXT");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.GROUND, "MVT", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.TYPE_B, request.rawMessage()))
                    .thenReturn(parsed);
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(7L);

            controller.receiveRawMessage(request);

            verify(messageInputPort).receiveMessage(
                    eq(MessageType.GROUND), eq("MVT"), eq("VP-BQR"), eq("SU1234"), eq("TEXT"), isNull());
        }

        @Test
        @DisplayName("ошибка парсинга пробрасывается наружу как MessageParsingException (не маскируется в 500)")
        void shouldPropagateParsingException() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.AFTN, "GARBAGE");

            MessageParsingException parsingException =
                    new MessageParsingException(RawMessageFormat.AFTN, "AFTN: не найдена строка priority");
            when(rawMessageParserService.parse(RawMessageFormat.AFTN, "GARBAGE"))
                    .thenThrow(parsingException);

            assertThatThrownBy(() -> controller.receiveRawMessage(request))
                    .isInstanceOf(MessageParsingException.class)
                    .isInstanceOf(IllegalArgumentException.class);

            verify(messageInputPort, never()).receiveMessage(any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("P2-4: callsign matching -> FI перед передачей в MessageInputPort (делегировано в RawMessageIngestSupport)")
    class CallsignMatchingIntegration {

        @Test
        @DisplayName("departureAirport/arrivalAirport/flightDate из запроса передаются в ingestSupport.resolveFlightId")
        void shouldForwardRequestContextToIngestSupport() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/AFL1234 LABEL/H1 TEXT", "UUEE", "ULLI");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.DOWNLINK, "H1", "VP-BQR", "AFL1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, request.rawMessage()))
                    .thenReturn(parsed);
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(ingestSupport.resolveFlightId(parsed, "UUEE", "ULLI", null)).thenReturn("SU1234");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), any()))
                    .thenReturn(4L);

            controller.receiveRawMessage(request);

            verify(ingestSupport).resolveFlightId(parsed, "UUEE", "ULLI", null);
            verify(messageInputPort).receiveMessage(
                    eq(MessageType.DOWNLINK), eq("H1"), eq("VP-BQR"), eq("SU1234"), eq("TEXT"), isNull());
        }

        @Test
        @DisplayName("ingestSupport не находит правило -> flightNumber из ParsedMessage передаётся как есть")
        void shouldKeepWhateverIngestSupportReturns() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, request.rawMessage()))
                    .thenReturn(parsed);
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), any()))
                    .thenReturn(2L);

            controller.receiveRawMessage(request);

            verify(messageInputPort).receiveMessage(
                    eq(MessageType.DOWNLINK), eq("H1"), eq("VP-BQR"), eq("SU1234"), eq("TEXT"), isNull());
        }
    }

    @Nested
    @DisplayName("P2-6: DLQ при сбое приёма")
    class DeadLetterQueueIntegration {

        @Test
        @DisplayName("ошибка парсинга -> captureFailure вызван с заявленным форматом/сырым телом ДО переброса исключения")
        void parsingFailureCapturedInDlqBeforeRethrow() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.AFTN, "GARBAGE", "UUEE", "ULLI");

            MessageParsingException parsingException =
                    new MessageParsingException(RawMessageFormat.AFTN, "AFTN: не найдена строка priority");
            when(rawMessageParserService.parse(RawMessageFormat.AFTN, "GARBAGE"))
                    .thenThrow(parsingException);
            when(deadLetterQueueService.captureFailure(any(), any(), any(), any()))
                    .thenReturn(DeadLetterMessage.builder().id(1L).build());

            assertThatThrownBy(() -> controller.receiveRawMessage(request))
                    .isSameAs(parsingException);

            ArgumentCaptor<DeadLetterRequestContext> contextCaptor = ArgumentCaptor.forClass(DeadLetterRequestContext.class);
            verify(deadLetterQueueService).captureFailure(eq(RawMessageFormat.AFTN), eq("GARBAGE"),
                    contextCaptor.capture(), eq(parsingException));
            assertThat(contextCaptor.getValue().departureAirport()).isEqualTo("UUEE");
            assertThat(contextCaptor.getValue().arrivalAirport()).isEqualTo("ULLI");
        }

        @Test
        @DisplayName("непредвиденный сбой messageInputPort.receiveMessage -> тоже captureFailure + переброс")
        void receiveMessageFailureCapturedInDlq() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, request.rawMessage()))
                    .thenReturn(parsed);
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");

            RuntimeException dbFailure = new IllegalStateException("eventprocessor unavailable");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), any()))
                    .thenThrow(dbFailure);
            when(deadLetterQueueService.captureFailure(any(), any(), any(), any()))
                    .thenReturn(DeadLetterMessage.builder().id(2L).build());

            assertThatThrownBy(() -> controller.receiveRawMessage(request))
                    .isSameAs(dbFailure);

            verify(deadLetterQueueService).captureFailure(eq(RawMessageFormat.ARINC_618),
                    eq(request.rawMessage()), any(DeadLetterRequestContext.class), eq(dbFailure));
        }

        @Test
        @DisplayName("успешный приём -> captureFailure НЕ вызывается")
        void successfulReceiveNeverTouchesDlq() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            ParsedMessage parsed = new ParsedMessage(
                    MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, request.rawMessage()))
                    .thenReturn(parsed);
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(messageInputPort.receiveMessage(any(), any(), any(), any(), any(), any())).thenReturn(1L);

            controller.receiveRawMessage(request);

            verify(deadLetterQueueService, never()).captureFailure(any(), any(), any(), any());
        }
    }
}
