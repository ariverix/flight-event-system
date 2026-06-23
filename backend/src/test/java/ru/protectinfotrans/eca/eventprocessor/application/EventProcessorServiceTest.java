package ru.protectinfotrans.eca.eventprocessor.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.conditions.port.in.FlightConditionLifecycleUseCase;
import ru.protectinfotrans.eca.customfields.port.in.FlightContextLifecycleUseCase;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.FlightStageEventRepositoryPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для EventProcessorService.
 *
 * <p>С фикса TOCTOU-гонки (ревью P2-1) вся персистентная/публикующая логика вынесена в
 * {@link MessagePersistenceTransaction} (отдельный {@code @Transactional}-бин — детали см. её
 * javadoc и {@link MessagePersistenceTransactionTest}). Этот класс тестирует ТОЛЬКО оркестрацию
 * {@code EventProcessorService}: делегирование, перехват {@code DataIntegrityViolationException}
 * и recovery-read при гонке, и {@code notifyFlightStageChange}.
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

    @Mock
    private MessagePersistenceTransaction messagePersistenceTransaction;

    @Mock
    private FlightStageEventRepositoryPort flightStageEventRepository;

    // P3-2: системный канал смены стадии (notifyFlightStageChange) обязан закрывать
    // per-flight контекст custom fields на терминальных стадиях (IN/SUMMARY).
    @Mock
    private FlightContextLifecycleUseCase flightContextLifecycleUseCase;

    // P3-3: тот же системный канал обязан авто-закрывать активные custom conditions рейса на
    // терминальных стадиях (IN/SUMMARY) — независимо от flightContextLifecycleUseCase выше.
    @Mock
    private FlightConditionLifecycleUseCase flightConditionLifecycleUseCase;

    private EventProcessorService service;

    @BeforeEach
    void setUp() {
        service = new EventProcessorService(
                messageRepository, eventPublisher, messagePersistenceTransaction, flightStageEventRepository,
                flightContextLifecycleUseCase, flightConditionLifecycleUseCase);
    }

    @Nested
    @DisplayName("receiveMessage — happy path (делегирование)")
    class ReceiveMessageDelegationTests {

        @Test
        @DisplayName("должен делегировать persistAndPublish с извлечённым externalMessageId и вернуть id")
        void shouldDelegateToPersistenceTransaction() {
            when(messagePersistenceTransaction.persistAndPublish(
                    eq(MessageType.DOWNLINK), eq("POSITION_REPORT"), eq("VP-BXX"), eq("SU100"),
                    eq("POS LAT=55.0 LON=37.0"), any(), isNull()))
                    .thenReturn(1L);

            Map<String, Object> metadata = Map.of("flightStage", "OFF");

            Long messageId = service.receiveMessage(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BXX", "SU100",
                    "POS LAT=55.0 LON=37.0", metadata);

            assertThat(messageId).isEqualTo(1L);
            verify(messagePersistenceTransaction).persistAndPublish(
                    MessageType.DOWNLINK, "POSITION_REPORT", "VP-BXX", "SU100",
                    "POS LAT=55.0 LON=37.0", metadata, null);
            verifyNoInteractions(eventPublisher);
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("должен извлечь externalMessageId из metadata и передать его в persistAndPublish")
        void shouldExtractExternalMessageIdAndDelegate() {
            when(messagePersistenceTransaction.persistAndPublish(
                    any(), any(), any(), any(), any(), any(), eq("ARINC-REF-100")))
                    .thenReturn(8L);

            Long messageId = service.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK",
                    Map.of("externalMessageId", "ARINC-REF-100"));

            assertThat(messageId).isEqualTo(8L);
            verify(messagePersistenceTransaction).persistAndPublish(
                    eq(MessageType.DOWNLINK), eq("STATUS"), eq("VP-BQR"), eq("SU1234"), eq("OK"),
                    any(), eq("ARINC-REF-100"));
        }

        @Test
        @DisplayName("должен относиться к пустой строке externalMessageId как к отсутствующей (передаёт null)")
        void shouldTreatBlankExternalMessageIdAsAbsent() {
            when(messagePersistenceTransaction.persistAndPublish(
                    any(), any(), any(), any(), any(), any(), isNull()))
                    .thenReturn(11L);

            service.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK",
                    Map.of("externalMessageId", "   "));

            verify(messagePersistenceTransaction).persistAndPublish(
                    any(), any(), any(), any(), any(), any(), isNull());
        }
    }

    @Nested
    @DisplayName("receiveMessage — TOCTOU-гонка (ревью P2-1)")
    class ReceiveMessageRaceRecoveryTests {

        @Test
        @DisplayName("DataIntegrityViolationException на persistAndPublish -> recovery-read находит запись "
                + "победителя, НЕ публикует событие повторно, не пробрасывает исключение наружу")
        void shouldRecoverGracefullyWhenConcurrentInsertWinsTheRace() {
            DataIntegrityViolationException duplicateKey = new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint "
                            + "\"idx_messages_external_message_id_unique\"");
            when(messagePersistenceTransaction.persistAndPublish(
                    any(), any(), any(), any(), any(), any(), eq("ARINC-REF-RACE")))
                    .thenThrow(duplicateKey);

            IncomingMessage winnerMessage = IncomingMessage.builder()
                    .id(42L)
                    .messageType(MessageType.DOWNLINK)
                    .templateName("STATUS")
                    .aircraftId("VP-BQR")
                    .externalMessageId("ARINC-REF-RACE")
                    .receivedAt(LocalDateTime.now())
                    .build();

            // recovery-read (REQUIRES_NEW, отдельная транзакция) находит запись победителя —
            // её транзакция уже закоммичена (READ COMMITTED видит результат).
            when(messageRepository.findByExternalMessageIdInNewTransaction("ARINC-REF-RACE"))
                    .thenReturn(Optional.of(winnerMessage));

            Long messageId = service.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK",
                    Map.of("externalMessageId", "ARINC-REF-RACE"));

            assertThat(messageId)
                    .as("проигравший гонку вызов должен вернуть id записи победителя, а не упасть наружу")
                    .isEqualTo(42L);

            verify(messageRepository).findByExternalMessageIdInNewTransaction("ARINC-REF-RACE");
            verify(eventPublisher, never()).publish(any(NormalizedEvent.class));
        }

        @Test
        @DisplayName("если recovery-read тоже не нашёл запись — перевыбрасывает исходное исключение "
                + "(не маскирует непредвиденную ситуацию)")
        void shouldRethrowOriginalExceptionWhenRecoveryReadFindsNothing() {
            DataIntegrityViolationException duplicateKey = new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint "
                            + "\"idx_messages_external_message_id_unique\"");
            when(messagePersistenceTransaction.persistAndPublish(
                    any(), any(), any(), any(), any(), any(), eq("ARINC-REF-GHOST")))
                    .thenThrow(duplicateKey);
            when(messageRepository.findByExternalMessageIdInNewTransaction("ARINC-REF-GHOST"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK",
                    Map.of("externalMessageId", "ARINC-REF-GHOST")))
                    .isSameAs(duplicateKey);

            verify(eventPublisher, never()).publish(any(NormalizedEvent.class));
        }

        @Test
        @DisplayName("DataIntegrityViolationException без externalMessageId перевыбрасывается без "
                + "попытки recovery-read (партиционный unique index не действует на NULL)")
        void shouldRethrowWithoutRecoveryWhenExternalMessageIdAbsent() {
            DataIntegrityViolationException otherViolation = new DataIntegrityViolationException(
                    "some unrelated constraint violation");
            when(messagePersistenceTransaction.persistAndPublish(
                    any(), any(), any(), any(), any(), any(), isNull()))
                    .thenThrow(otherViolation);

            assertThatThrownBy(() -> service.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "OK", Map.of()))
                    .isSameAs(otherViolation);

            verifyNoInteractions(messageRepository);
        }
    }

    @Nested
    @DisplayName("notifyFlightStageChange")
    class FlightStageChangeTests {

        @Test
        @DisplayName("должен опубликовать событие изменения стадии полёта без сохранения в messages")
        void shouldPublishStageChangeEventWithoutSavingToMessages() {
            // Act
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.OFF);

            // Assert: смена стадии — системное событие, в messages не пишем (НО durable журнал
            // flight_stage_events — отдельная таблица, V29, см. тест ниже).
            verifyNoInteractions(messageRepository);
            verifyNoInteractions(messagePersistenceTransaction);

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

        @Test
        @DisplayName("P2-5: должен записать факт смены стадии в durable журнал flight_stage_events "
                + "(источник Off-таймстампа для POSITION-критерия \"not reported\")")
        void shouldRecordFlightStageEventForDurableOffTimestamp() {
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.OFF);

            ArgumentCaptor<FlightStageEvent> captor = ArgumentCaptor.forClass(FlightStageEvent.class);
            verify(flightStageEventRepository).save(captor.capture());

            FlightStageEvent saved = captor.getValue();
            assertThat(saved.getAircraftId()).isEqualTo("VP-BXX");
            assertThat(saved.getFlightNumber()).isEqualTo("SU100");
            assertThat(saved.getStage()).isEqualTo(FlightStage.OFF);
            assertThat(saved.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("P2-5: occurredAt записи в flight_stage_events должен совпадать с timestamp "
                + "опубликованного NormalizedEvent (один и тот же момент, не два отдельных now())")
        void shouldUseSameTimestampForStageEventAndNormalizedEvent() {
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.OFF);

            ArgumentCaptor<FlightStageEvent> stageEventCaptor = ArgumentCaptor.forClass(FlightStageEvent.class);
            verify(flightStageEventRepository).save(stageEventCaptor.capture());

            ArgumentCaptor<NormalizedEvent> normalizedEventCaptor = ArgumentCaptor.forClass(NormalizedEvent.class);
            verify(eventPublisher).publish(normalizedEventCaptor.capture());

            assertThat(stageEventCaptor.getValue().getOccurredAt())
                    .isEqualTo(normalizedEventCaptor.getValue().timestamp());
        }

        @Test
        @DisplayName("P3-2: должен уведомить FlightContextLifecycleUseCase о смене стадии "
                + "(закрытие контекста custom fields на IN/SUMMARY — решение принимается там)")
        void shouldNotifyFlightContextLifecycleOnStageChange() {
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.IN);

            verify(flightContextLifecycleUseCase).onFlightStageChanged("VP-BXX", "SU100", FlightStage.IN);
        }

        @Test
        @DisplayName("P3-3: должен уведомить FlightConditionLifecycleUseCase о смене стадии "
                + "(авто-закрытие активных custom conditions на IN/SUMMARY — решение принимается там)")
        void shouldNotifyFlightConditionLifecycleOnStageChange() {
            service.notifyFlightStageChange("VP-BXX", "SU100", FlightStage.IN);

            verify(flightConditionLifecycleUseCase).onFlightStageChanged("VP-BXX", "SU100", FlightStage.IN);
        }
    }
}
