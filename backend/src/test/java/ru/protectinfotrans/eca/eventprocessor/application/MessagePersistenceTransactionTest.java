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
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldExtractionUseCase;
import ru.protectinfotrans.eca.customfields.port.in.FlightContextLifecycleUseCase;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.FlightStageEventRepositoryPort;
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

    @Mock
    private FlightStageEventRepositoryPort flightStageEventRepository;

    // P3-2: движок custom fields — extract() вызывается на каждое сообщение (см. javadoc
    // persistAndPublish), onFlightStageChanged() — на каждую обнаруженную смену стадии.
    @Mock
    private CustomFieldExtractionUseCase customFieldExtractionUseCase;

    @Mock
    private FlightContextLifecycleUseCase flightContextLifecycleUseCase;

    private MessagePersistenceTransaction persistenceTransaction;

    @BeforeEach
    void setUp() {
        persistenceTransaction = new MessagePersistenceTransaction(
                messageRepository, eventPublisher, flightStageEventRepository, new ObjectMapper(),
                customFieldExtractionUseCase, flightContextLifecycleUseCase);
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
        @DisplayName("P3-2: должен вызвать извлечение custom fields для сохранённого сообщения "
                + "ДО публикации NormalizedEvent")
        void shouldExtractCustomFieldsBeforePublishingEvent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(30L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("POSITION_REPORT")
                    .aircraftId("VP-BXX")
                    .flightNumber("SU100")
                    .content("POS LAT=55.0 LON=37.0")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            Map<String, Object> metadata = Map.of("flightStage", "OFF");

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BXX", "SU100",
                    "POS LAT=55.0 LON=37.0", metadata, null);

            verify(customFieldExtractionUseCase).extract(
                    eq(30L), eq(MessageType.DOWNLINK), eq("POSITION_REPORT"), eq("VP-BXX"), eq("SU100"),
                    eq("POS LAT=55.0 LON=37.0"), eq(metadata));

            // порядок: extract() должен произойти ДО publish() NormalizedEvent
            org.mockito.InOrder order = inOrder(customFieldExtractionUseCase, eventPublisher);
            order.verify(customFieldExtractionUseCase).extract(any(), any(), any(), any(), any(), any(), any());
            order.verify(eventPublisher).publish(any(NormalizedEvent.class));
        }

        @Test
        @DisplayName("P3-2: должен закрыть контекст custom fields рейса при OOOI-метке IN, "
                + "встроенной в обычное входящее сообщение")
        void shouldCloseFlightContextWhenStageEmbeddedInMessageIsIn() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(31L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("OOOI")
                    .aircraftId("VP-BZZ")
                    .flightNumber("SU200")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "OOOI", "VP-BZZ", "SU200", "IN UUEE",
                    Map.of("flightStage", "IN"), null);

            verify(flightContextLifecycleUseCase).onFlightStageChanged("VP-BZZ", "SU200", FlightStage.IN);
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
        @DisplayName("P2-5: OOOI-метка OFF, разобранная ИЗ входящего сообщения (ARINC 618), должна "
                + "попасть в durable журнал flight_stage_events — не только через notifyFlightStageChange")
        void shouldRecordFlightStageEventWhenStageEmbeddedInMessageMetadata() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(20L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("OOOI")
                    .aircraftId("VP-BZZ")
                    .flightNumber("SU200")
                    .receivedAt(LocalDateTime.of(2026, 6, 19, 10, 0))
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "OOOI", "VP-BZZ", "SU200", "OFF UUEE",
                    Map.of("flightStage", "OFF"), null);

            ArgumentCaptor<FlightStageEvent> captor = ArgumentCaptor.forClass(FlightStageEvent.class);
            verify(flightStageEventRepository).save(captor.capture());
            FlightStageEvent saved = captor.getValue();
            assertThat(saved.getAircraftId()).isEqualTo("VP-BZZ");
            assertThat(saved.getFlightNumber()).isEqualTo("SU200");
            assertThat(saved.getStage()).isEqualTo(FlightStage.OFF);
            assertThat(saved.getOccurredAt()).isEqualTo(savedMessage.getReceivedAt());
        }

        @Test
        @DisplayName("P2-5: сообщение без flightStage в метаданных НЕ должно писать в flight_stage_events")
        void shouldNotRecordFlightStageEventWhenStageAbsent() {
            IncomingMessage savedMessage = IncomingMessage.builder()
                    .id(21L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .receivedAt(LocalDateTime.now())
                    .build();

            when(messageRepository.saveAndFlush(any(IncomingMessage.class))).thenReturn(savedMessage);

            persistenceTransaction.persistAndPublish(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK", Map.of(), null);

            verifyNoInteractions(flightStageEventRepository);
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
