package ru.protectinfotrans.eca.eventprocessor.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для EventProcessorService.
 *
 * См. диплом: раздел 1.3.5 (UC-06)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventProcessorService")
class EventProcessorServiceTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private EventPublisherPort eventPublisher;

    private EventProcessorService service;

    @BeforeEach
    void setUp() {
        service = new EventProcessorService(messageRepository, eventPublisher, new ObjectMapper());
    }

    @Nested
    @DisplayName("receiveMessage")
    class ReceiveMessageTests {

        @Test
        @DisplayName("должен сохранить сообщение в БД и опубликовать NormalizedEvent")
        void shouldSaveMessageAndPublishEvent() {
            // Arrange
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(1L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BXX")
                    .flightNumber("SU100")
                    .content("POS LAT=55.0 LON=37.0")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("flightStage", "OFF");

            // Act
            Long messageId = service.receiveMessage(
                    MessageType.DOWNLINK,
                    "POSITION_REPORT",
                    "VP-BXX",
                    "SU100",
                    "POS LAT=55.0 LON=37.0",
                    metadata
            );

            // Assert
            assertThat(messageId).isEqualTo(1L);

            // Verify save
            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).save(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();
            assertThat(captured.getMessageType()).isEqualTo(MessageType.DOWNLINK);
            assertThat(captured.getTemplateName()).isEqualTo("POSITION_REPORT");
            assertThat(captured.getAircraftId()).isEqualTo("VP-BXX");

            // Verify event published
            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            NormalizedEvent event = eventCaptor.getValue();
            assertThat(event.messageId()).isEqualTo(1L);
            assertThat(event.messageType()).isEqualTo(MessageType.DOWNLINK);
            assertThat(event.aircraftId()).isEqualTo("VP-BXX");
            assertThat(event.flightStage()).isEqualTo(FlightStage.OFF);
        }

        @Test
        @DisplayName("должен обработать сообщение без метаданных")
        void shouldHandleMessageWithoutMetadata() {
            // Arrange
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(2L)
                    .messageType(MessageType.UPLINK)
                    .templateName("WEATHER_REQUEST")
                    .aircraftId("VP-BYY")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            // Act
            Long messageId = service.receiveMessage(
                    MessageType.UPLINK,
                    "WEATHER_REQUEST",
                    "VP-BYY",
                    null,
                    "REQ METAR UUEE",
                    null
            );

            // Assert
            assertThat(messageId).isEqualTo(2L);

            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            NormalizedEvent event = eventCaptor.getValue();
            assertThat(event.flightStage()).isNull();
        }

        @Test
        @DisplayName("должен извлечь FlightStage из метаданных")
        void shouldExtractFlightStageFromMetadata() {
            // Arrange
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(3L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("OOOI")
                    .aircraftId("VP-BZZ")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("flightStage", "ON");

            // Act
            service.receiveMessage(
                    MessageType.DOWNLINK,
                    "OOOI",
                    "VP-BZZ",
                    "SU200",
                    "ON UUEE",
                    metadata
            );

            // Assert
            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            NormalizedEvent event = eventCaptor.getValue();
            assertThat(event.flightStage()).isEqualTo(FlightStage.ON);
        }

        @Test
        @DisplayName("должен извлечь positionSource и estimatedPosition=true из метаданных")
        void shouldExtractPositionSourceAndEstimatedFlag() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(4L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of(
                    "positionSource", "ADS_B",
                    "estimatedPosition", true
            );

            service.receiveMessage(
                    MessageType.DOWNLINK,
                    "POSITION_REPORT",
                    "VP-BQR",
                    "SU1234",
                    "POS LAT=55.0 LON=37.0 (EST)",
                    metadata
            );

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).save(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();

            assertThat(captured.getPositionSource()).isEqualTo(PositionSource.ADS_B);
            assertThat(captured.isEstimatedPosition()).isTrue();
        }

        @Test
        @DisplayName("должен оставить positionSource=null и estimatedPosition=false без соответствующих метаданных")
        void shouldDefaultPositionFieldsWhenMetadataAbsent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(5L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            service.receiveMessage(
                    MessageType.DOWNLINK,
                    "STATUS",
                    "VP-BQR",
                    "SU1234",
                    "OK",
                    Map.of("flightStage", "OUT")
            );

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).save(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();

            assertThat(captured.getPositionSource()).isNull();
            assertThat(captured.isEstimatedPosition()).isFalse();
        }

        @Test
        @DisplayName("должен проставить positionSource=ACARS (fallback) если есть latitude/longitude, но нет явного positionSource")
        void shouldDefaultPositionSourceToAcarsWhenCoordinatesPresentWithoutExplicitSource() {
            // Регрессия: UI (DemoPage/MessageSimulator) шлёт {"latitude":..,"longitude":..}
            // без positionSource — без fallback позиционный отчёт навсегда не закрывает
            // POSITION_REPORTED-критерий (требует position_source IS NOT NULL, P1-1).
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(6L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("latitude", 55.7558, "longitude", 37.6173);

            service.receiveMessage(
                    MessageType.DOWNLINK,
                    "POSITION_REPORT",
                    "VP-BQR",
                    "SU1234",
                    "POS LAT=55.7558 LON=37.6173",
                    metadata
            );

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).save(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();

            assertThat(captured.getPositionSource()).isEqualTo(PositionSource.ACARS);
            assertThat(captured.isEstimatedPosition()).isFalse();
        }

        @Test
        @DisplayName("не должен переопределять явный positionSource даже если есть latitude/longitude")
        void shouldNotOverrideExplicitPositionSourceWhenCoordinatesPresent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(7L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.save(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of(
                    "latitude", 55.7558, "longitude", 37.6173,
                    "positionSource", "RADAR"
            );

            service.receiveMessage(
                    MessageType.DOWNLINK,
                    "POSITION_REPORT",
                    "VP-BQR",
                    "SU1234",
                    "POS LAT=55.7558 LON=37.6173",
                    metadata
            );

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).save(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();

            assertThat(captured.getPositionSource()).isEqualTo(PositionSource.RADAR);
        }
    }

    @Nested
    @DisplayName("notifyFlightStageChange")
    class FlightStageChangeTests {

        @Test
        @DisplayName("должен опубликовать событие изменения стадии полёта без сохранения в БД")
        void shouldPublishStageChangeEventWithoutSaving() {
            // Act
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.OFF);

            // Assert
            verify(messageRepository, never()).save(any());

            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            NormalizedEvent event = eventCaptor.getValue();

            assertThat(event.messageId()).isNull();
            assertThat(event.messageType()).isNull();
            assertThat(event.templateName()).isNull();
            assertThat(event.aircraftId()).isEqualTo("VP-BXX");
            assertThat(event.flightNumber()).isEqualTo("SU100");
            assertThat(event.flightStage()).isEqualTo(FlightStage.OFF);
        }

        @Test
        @DisplayName("должен обработать изменение стадии для всех OOOI стадий")
        void shouldHandleAllFlightStages() {
            // Test all OOOI stages
            for (FlightStage stage : FlightStage.values()) {
                service.notifyFlightStageChange("VP-TEST", "TEST100", stage);
            }

            verify(eventPublisher, times(FlightStage.values().length)).publish(any(NormalizedEvent.class));
        }
    }
}
