package ru.protectinfotrans.eca.eventprocessor.adapter.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.application.EventProcessorService;
import ru.protectinfotrans.eca.eventprocessor.application.MessageQueryService;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.dto.FlightStageChangeRequest;
import ru.protectinfotrans.eca.eventprocessor.dto.IncomingMessageRequest;
import ru.protectinfotrans.eca.eventprocessor.dto.MessageResponse;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для MessageController (UC-06: приём сообщений и журнал).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageController")
class MessageControllerTest {

    @Mock
    private EventProcessorService eventProcessorService;

    @Mock
    private MessageQueryService messageQueryService;

    private MessageController controller;

    @BeforeEach
    void setUp() {
        controller = new MessageController(eventProcessorService, messageQueryService, new ObjectMapper());
    }

    @Nested
    @DisplayName("Приём сообщений")
    class ReceiveMessage {

        @Test
        @DisplayName("должен принять сообщение и вернуть id")
        void shouldReceiveMessage() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", "VP-BAB", "SU1234", null);

            when(eventProcessorService.receiveMessage(
                    eq(MessageType.DOWNLINK), eq("STATUS"), eq("VP-BAB"), eq("SU1234"), isNull(), isNull()))
                    .thenReturn(42L);

            ResponseEntity<MessageController.MessageReceivedResponse> response =
                    controller.receiveMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().messageId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("должен распарсить metadataJson и передать его сервису")
        void shouldParseMetadataJson() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.UPLINK, "CLEARANCE", "VP-BAB", "SU1234", "{\"key\":\"value\"}");

            when(eventProcessorService.receiveMessage(any(), any(), any(), any(), any(), anyMap()))
                    .thenReturn(1L);

            controller.receiveMessage(request);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(eventProcessorService).receiveMessage(
                    eq(MessageType.UPLINK), eq("CLEARANCE"), eq("VP-BAB"), eq("SU1234"), isNull(), captor.capture());

            assertThat(captor.getValue()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("должен игнорировать некорректный metadataJson без ошибки")
        void shouldIgnoreInvalidMetadataJson() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", "VP-BAB", "SU1234", "{not-valid");

            when(eventProcessorService.receiveMessage(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(2L);

            ResponseEntity<MessageController.MessageReceivedResponse> response =
                    controller.receiveMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(eventProcessorService).receiveMessage(
                    eq(MessageType.DOWNLINK), eq("STATUS"), eq("VP-BAB"), eq("SU1234"), isNull(), isNull());
        }

        @Test
        @DisplayName("должен игнорировать пустой metadataJson")
        void shouldIgnoreBlankMetadataJson() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", "VP-BAB", "SU1234", "   ");

            when(eventProcessorService.receiveMessage(any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(3L);

            controller.receiveMessage(request);

            verify(eventProcessorService).receiveMessage(
                    eq(MessageType.DOWNLINK), eq("STATUS"), eq("VP-BAB"), eq("SU1234"), isNull(), isNull());
        }
    }

    @Nested
    @DisplayName("Изменение стадии полёта")
    class FlightStageChange {

        @Test
        @DisplayName("должен уведомить сервис об изменении стадии")
        void shouldNotifyStageChange() {
            FlightStageChangeRequest request = new FlightStageChangeRequest("VP-BAB", "SU1234", FlightStage.OFF);

            ResponseEntity<Void> response = controller.notifyFlightStageChange(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(eventProcessorService).notifyFlightStageChange("VP-BAB", "SU1234", FlightStage.OFF);
        }
    }

    @Nested
    @DisplayName("Журнал сообщений")
    class GetMessages {

        @Test
        @DisplayName("должен вернуть страницу сообщений с маппингом в DTO")
        void shouldReturnMappedMessages() {
            IncomingMessage message = IncomingMessage.builder()
                    .id(1L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BAB")
                    .flightNumber("SU1234")
                    .receivedAt(LocalDateTime.now())
                    .metadataJson(null)
                    .build();

            Page<IncomingMessage> page = new PageImpl<>(List.of(message));
            when(messageQueryService.findMessages(eq("VP-BAB"), eq(MessageType.DOWNLINK), any()))
                    .thenReturn(page);

            ResponseEntity<Page<MessageResponse>> response = controller.getMessages(
                    "VP-BAB", MessageType.DOWNLINK, PageRequest.of(0, 20));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getContent()).hasSize(1);
            assertThat(response.getBody().getContent().get(0).aircraftId()).isEqualTo("VP-BAB");
        }

        @Test
        @DisplayName("должен вернуть пустую страницу если нет сообщений")
        void shouldReturnEmptyPageWhenNoMessages() {
            when(messageQueryService.findMessages(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            ResponseEntity<Page<MessageResponse>> response = controller.getMessages(
                    null, null, PageRequest.of(0, 20));

            assertThat(response.getBody().getContent()).isEmpty();
        }
    }
}
