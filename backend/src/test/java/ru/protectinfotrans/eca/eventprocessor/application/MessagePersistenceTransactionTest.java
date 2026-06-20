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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для {@link MessagePersistenceTransaction} — транзакционного слоя
 * дедуп-проверка+save+publish, вынесенного из {@code EventProcessorService} при фиксе
 * TOCTOU-гонки (ревью P2-1). См. её javadoc для объяснения, почему это отдельный бин.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessagePersistenceTransaction")
class MessagePersistenceTransactionTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Mock
    private EventPublisherPort eventPublisher;

    private MessagePersistenceTransaction persistenceTransaction;

    @BeforeEach
    void setUp() {
        persistenceTransaction = new MessagePersistenceTransaction(messageRepository, eventPublisher, new ObjectMapper());
    }

    @Nested
    @DisplayName("persistAndPublish")
    class PersistAndPublishTests {

        @Test
        @DisplayName("должен сохранить сообщение в БД и опубликовать NormalizedEvent")
        void shouldSaveMessageAndPublishEvent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(1L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BXX")
                    .flightNumber("SU100")
                    .content("POS LAT=55.0 LON=37.0")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("flightStage", "OFF");

            Long messageId = persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BXX", "SU100",
                    "POS LAT=55.0 LON=37.0", metadata, null);

            assertThat(messageId).isEqualTo(1L);

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();
            assertThat(captured.getMessageType()).isEqualTo(MessageType.DOWNLINK);
            assertThat(captured.getTemplateName()).isEqualTo("POSITION_REPORT");
            assertThat(captured.getAircraftId()).isEqualTo("VP-BXX");

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
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(2L)
                    .messageType(MessageType.UPLINK)
                    .templateName("WEATHER_REQUEST")
                    .aircraftId("VP-BYY")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Long messageId = persistenceTransaction.persistAndPublish(
                    MessageType.UPLINK, "WEATHER_REQUEST", "VP-BYY", null, "REQ METAR UUEE", null, null);

            assertThat(messageId).isEqualTo(2L);

            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().flightStage()).isNull();
        }

        @Test
        @DisplayName("должен извлечь FlightStage из метаданных")
        void shouldExtractFlightStageFromMetadata() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(3L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("OOOI")
                    .aircraftId("VP-BZZ")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "OOOI", "VP-BZZ", "SU200", "ON UUEE",
                    Map.of("flightStage", "ON"), null);

            ArgumentCaptor<NormalizedEvent> eventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().flightStage()).isEqualTo(FlightStage.ON);
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

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("positionSource", "ADS_B", "estimatedPosition", true);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BQR", "SU1234",
                    "POS LAT=55.0 LON=37.0 (EST)", metadata, null);

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(messageCaptor.capture());
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

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK",
                    Map.of("flightStage", "OUT"), null);

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(messageCaptor.capture());
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

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("latitude", 55.7558, "longitude", 37.6173);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BQR", "SU1234",
                    "POS LAT=55.7558 LON=37.6173", metadata, null);

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(messageCaptor.capture());
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

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of(
                    "latitude", 55.7558, "longitude", 37.6173,
                    "positionSource", "RADAR"
            );

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BQR", "SU1234",
                    "POS LAT=55.7558 LON=37.6173", metadata, null);

            ArgumentCaptor<IncomingMessage> messageCaptor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(messageCaptor.capture());
            IncomingMessage captured = messageCaptor.getValue();

            assertThat(captured.getPositionSource()).isEqualTo(PositionSource.RADAR);
        }
    }

    @Nested
    @DisplayName("Идемпотентность шлюза по externalMessageId (P2-1)")
    class GatewayIdempotencyTests {

        @Test
        @DisplayName("должен сохранить externalMessageId на новой записи при первом приёме")
        void shouldPersistExternalMessageIdOnFirstDelivery() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(8L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.findByExternalMessageId("ARINC-REF-100")).thenReturn(Optional.empty());
            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Long messageId = persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK", Map.of(), "ARINC-REF-100");

            assertThat(messageId).isEqualTo(8L);

            ArgumentCaptor<IncomingMessage> captor = ArgumentCaptor.forClass(IncomingMessage.class);
            verify(messageRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getExternalMessageId()).isEqualTo("ARINC-REF-100");

            verify(eventPublisher).publish(any(NormalizedEvent.class));
        }

        @Test
        @DisplayName("должен вернуть id ранее сохранённого сообщения и НЕ сохранять/публиковать повторно при дубле")
        void shouldShortCircuitOnDuplicateExternalMessageId() {
            IncomingMessage alreadyPersisted = IncomingMessage.builder()
                    .id(9L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .externalMessageId("ARINC-REF-101")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.findByExternalMessageId("ARINC-REF-101"))
                    .thenReturn(Optional.of(alreadyPersisted));

            Long messageId = persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK", Map.of(), "ARINC-REF-101");

            assertThat(messageId).isEqualTo(9L);

            verify(messageRepository, never()).saveAndFlush(any());
            verify(eventPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("без externalMessageId — дедуп-проверка не выполняется, сообщение сохраняется как обычно")
        void shouldNotDedupeWhenExternalMessageIdAbsent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(10L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Long messageId = persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK", Map.of(), null);

            assertThat(messageId).isEqualTo(10L);
            verify(messageRepository, never()).findByExternalMessageId(any());
            verify(messageRepository).saveAndFlush(any(IncomingMessage.class));
            verify(eventPublisher).publish(any(NormalizedEvent.class));
        }
    }
}
