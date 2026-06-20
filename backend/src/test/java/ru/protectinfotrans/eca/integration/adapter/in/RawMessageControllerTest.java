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
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;
import ru.protectinfotrans.eca.integration.parser.RawMessageParserService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для {@link RawMessageController} (P2-2: приём сырых сообщений «борт-земля»).
 *
 * <p>Контроллер живёт в модуле {@code integration} (не {@code eventprocessor}) и вызывает
 * {@link MessageInputPort} (входной порт {@code eventprocessor}, named interface {@code port-in})
 * напрямую — без этого возник бы цикл границ модулей, см. javadoc {@link RawMessageController}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RawMessageController")
class RawMessageControllerTest {

    @Mock
    private RawMessageParserService rawMessageParserService;

    @Mock
    private MessageInputPort messageInputPort;

    private RawMessageController controller;

    @BeforeEach
    void setUp() {
        controller = new RawMessageController(rawMessageParserService, messageInputPort);
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

            when(rawMessageParserService.parse(RawMessageFormat.AFTN, "GARBAGE"))
                    .thenThrow(new MessageParsingException(RawMessageFormat.AFTN, "AFTN: не найдена строка priority"));

            assertThatThrownBy(() -> controller.receiveRawMessage(request))
                    .isInstanceOf(MessageParsingException.class)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
