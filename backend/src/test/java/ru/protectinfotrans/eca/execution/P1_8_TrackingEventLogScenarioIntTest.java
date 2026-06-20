package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.adapter.out.persistence.TrackingEventLogJpaRepository;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.domain.TrackingEventType;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-8 (часть 2 — логика записи): приёмочные тесты Event Log класса Tracking (SITA) — запись
 * SEQUENCE_STARTED/STEP_COMPLETED/SEQUENCE_STOPPED/SEQUENCE_ABORTED, гейтинг флагом
 * {@code Sequence#isLoggingEnabled()}, отсутствие дублей при идемпотентных no-op (P1-6/P1-7),
 * корректность correlationId.
 *
 * <p>Демо-борт VP-BQR, рейс SU1234 — единый стиль с остальными P1-* сценарными тестами.
 */
@DisplayName("P1-8: Event Log класса Tracking — запись событий")
class P1_8_TrackingEventLogScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private SequenceRepositoryPort sequenceRepository;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Autowired
    private TrackingEventLogJpaRepository trackingEventLogRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createSequenceWithTwoActionSteps(String name) {
        SequenceCreateRequest createReq =
                new SequenceCreateRequest(name, "P1-8 сценарный тест", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Step 1 — raise condition",
                StepType.ACTION,
                "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1-8 marker\",\"alertLevel\":\"LOW\"}",
                null,
                TransitionAction.CONTINUE, null, false,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Step 2 — close condition (END)",
                StepType.ACTION,
                "{\"actionType\":\"CLOSE_CONDITION\",\"conditionName\":\"P1-8 marker\"}",
                null,
                TransitionAction.END, null, false,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private Long createWaitForAckSequence(String name) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(
                name, "P1-8 контекст идемпотентности", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Ждать ACK",
                StepType.WAIT,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                600,
                TransitionAction.END, null, false,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private void disableLogging(Long sequenceId) {
        Sequence sequence = sequenceRepository.findById(sequenceId).orElseThrow();
        sequence.setLoggingEnabled(false);
        sequenceRepository.save(sequence);
    }

    private ExecutionInstance findInstance(Long sequenceId, String aircraftId) {
        return executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                .getContent().stream()
                .filter(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No instance found for sequence " + sequenceId));
    }

    private List<TrackingEventLog> findTrackingEvents(Long sequenceId) {
        return trackingEventLogRepository.findAll().stream()
                .filter(e -> e.getSequenceId().equals(sequenceId))
                .toList();
    }

    /**
     * {@code ExecutionService.processEvent} — {@code @ApplicationModuleListener} (мета-аннотирован
     * {@code @Async}) — реальный вызов диспетчеризуется в async-исполнитель Spring Modulith, а не
     * выполняется синхронно в вызывающем потоке (см. javadoc {@code P1_7_...ScenarioIntTest}).
     * Ждём появления записи журнала вместо предположения о синхронности.
     */
    private void awaitTrackingEventCount(Long sequenceId, TrackingEventType eventType, long expectedCount) {
        long deadline = System.currentTimeMillis() + 10_000;
        long lastSeen = -1;
        while (System.currentTimeMillis() < deadline) {
            lastSeen = findTrackingEvents(sequenceId).stream()
                    .filter(e -> e.getEventType() == eventType).count();
            if (lastSeen >= expectedCount) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Expected " + expectedCount + " " + eventType + " tracking events for sequence "
                + sequenceId + " within timeout, last seen count: " + lastSeen);
    }

    // ============================================================
    // 1. logging_enabled = true: SEQUENCE_STARTED + STEP_COMPLETED* + SEQUENCE_STOPPED/ABORTED
    // ============================================================
    @Nested
    @DisplayName("1. logging_enabled=true — журнал содержит полный путь выполнения")
    class LoggingEnabledTests {

        @Test
        @DisplayName("успешное завершение (END): SEQUENCE_STARTED, 2x STEP_COMPLETED, SEQUENCE_STOPPED")
        void completedRunWritesFullTrackingTrail() {
            CorrelationContext.putCorrelationId("corr-p1-8-completed");
            try {
                Long sequenceId = createSequenceWithTwoActionSteps("P1-8 completed demo");

                executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

                ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
                assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

                List<TrackingEventLog> events = findTrackingEvents(sequenceId);
                assertThat(events).extracting(TrackingEventLog::getEventType)
                        .containsExactlyInAnyOrder(
                                TrackingEventType.SEQUENCE_STARTED,
                                TrackingEventType.STEP_COMPLETED,
                                TrackingEventType.STEP_COMPLETED,
                                TrackingEventType.SEQUENCE_STOPPED
                        );

                TrackingEventLog started = events.stream()
                        .filter(e -> e.getEventType() == TrackingEventType.SEQUENCE_STARTED)
                        .findFirst().orElseThrow();
                assertThat(started.getSequenceId()).isEqualTo(sequenceId);
                assertThat(started.getAircraftId()).isEqualTo(AIRCRAFT_ID);
                assertThat(started.getFlightNumber()).isEqualTo(FLIGHT_NUMBER);
                assertThat(started.getInstanceId()).isEqualTo(instance.getId());
                assertThat(started.getCorrelationId()).isEqualTo("corr-p1-8-completed");

                List<TrackingEventLog> stepEvents = events.stream()
                        .filter(e -> e.getEventType() == TrackingEventType.STEP_COMPLETED)
                        .sorted((a, b) -> a.getStepIndex().compareTo(b.getStepIndex()))
                        .toList();
                assertThat(stepEvents).extracting(TrackingEventLog::getStepIndex).containsExactly(1, 2);
                assertThat(stepEvents).allSatisfy(e -> {
                    assertThat(e.getStepResult()).isEqualTo(StepResult.SUCCESS);
                    assertThat(e.getInstanceId()).isEqualTo(instance.getId());
                    assertThat(e.getAircraftId()).isEqualTo(AIRCRAFT_ID);
                    assertThat(e.getCorrelationId()).isEqualTo("corr-p1-8-completed");
                    assertThat(e.getDetailsJson()).isNotNull();
                });

                TrackingEventLog stopped = events.stream()
                        .filter(e -> e.getEventType() == TrackingEventType.SEQUENCE_STOPPED)
                        .findFirst().orElseThrow();
                assertThat(stopped.getInstanceId()).isEqualTo(instance.getId());
                assertThat(stopped.getCorrelationId()).isEqualTo("corr-p1-8-completed");
            } finally {
                CorrelationContext.clear();
            }
        }

        @Test
        @DisplayName("ABORT-переход: SEQUENCE_STARTED, STEP_COMPLETED (FAILURE), SEQUENCE_ABORTED")
        void abortedRunWritesAbortedEvent() {
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P1-8 aborted demo", "P1-8 сценарный тест", null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Step 1 — always fails",
                    StepType.EVALUATE,
                    "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"SUMMARY\"}",
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);
            Long sequenceId = created.id();

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);

            List<TrackingEventLog> events = findTrackingEvents(sequenceId);
            assertThat(events).extracting(TrackingEventLog::getEventType)
                    .containsExactlyInAnyOrder(
                            TrackingEventType.SEQUENCE_STARTED,
                            TrackingEventType.STEP_COMPLETED,
                            TrackingEventType.SEQUENCE_ABORTED
                    );

            TrackingEventLog stepEvent = events.stream()
                    .filter(e -> e.getEventType() == TrackingEventType.STEP_COMPLETED)
                    .findFirst().orElseThrow();
            assertThat(stepEvent.getStepResult()).isEqualTo(StepResult.FAILURE);

            TrackingEventLog aborted = events.stream()
                    .filter(e -> e.getEventType() == TrackingEventType.SEQUENCE_ABORTED)
                    .findFirst().orElseThrow();
            assertThat(aborted.getInstanceId()).isEqualTo(instance.getId());
        }

        @Test
        @DisplayName("stop-критерий (checkStopCriterionTransactional) пишет SEQUENCE_ABORTED")
        void stopCriterionAbortWritesAbortedEvent() {
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P1-8 stop-criterion demo", "P1-8 сценарный тест", null, stopCriteria);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Ждать ACK (никогда не придёт)",
                    StepType.WAIT,
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                            + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                    600,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);
            Long sequenceId = created.id();

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            NormalizedEvent stageEvent = new NormalizedEvent(
                    900L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now());

            executionService.checkStopCriterionTransactional(instance.getId(), stageEvent);

            ExecutionInstance afterStop = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterStop.getStatus()).isEqualTo(ExecutionStatus.ABORTED);

            List<TrackingEventLog> events = findTrackingEvents(sequenceId);
            assertThat(events).extracting(TrackingEventLog::getEventType)
                    .contains(TrackingEventType.SEQUENCE_ABORTED);
        }
    }

    // ============================================================
    // 2. logging_enabled = false: журнал гейтится — НЕТ записей
    // ============================================================
    @Nested
    @DisplayName("2. logging_enabled=false — журнал НЕ пишется")
    class LoggingDisabledTests {

        @Test
        @DisplayName("полный прогон (старт + 2 шага + завершение) при logging_enabled=false не создаёт ни одной записи")
        void disabledLoggingProducesNoTrackingEvents() {
            Long sequenceId = createSequenceWithTwoActionSteps("P1-8 disabled demo");
            disableLogging(sequenceId);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            assertThat(findTrackingEvents(sequenceId))
                    .as("logging_enabled=false должен полностью гейтить запись в tracking_event_log")
                    .isEmpty();
        }

        @Test
        @DisplayName("ABORT при logging_enabled=false не создаёт запись SEQUENCE_ABORTED")
        void disabledLoggingProducesNoAbortEvent() {
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P1-8 disabled stop demo", "P1-8 сценарный тест", null, stopCriteria);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Ждать ACK (никогда не придёт)",
                    StepType.WAIT,
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                            + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                    600,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);
            Long sequenceId = created.id();
            disableLogging(sequenceId);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);

            NormalizedEvent stageEvent = new NormalizedEvent(
                    901L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now());
            executionService.checkStopCriterionTransactional(instance.getId(), stageEvent);

            ExecutionInstance afterStop = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterStop.getStatus()).isEqualTo(ExecutionStatus.ABORTED);

            assertThat(findTrackingEvents(sequenceId)).isEmpty();
        }
    }

    // ============================================================
    // 3. Идемпотентность — повторная доставка/resume не плодит дубли STEP_COMPLETED
    // ============================================================
    @Nested
    @DisplayName("3. Идемпотентность — повторная доставка не дублирует записи журнала")
    class IdempotencyTests {

        @Test
        @DisplayName("повторный tryResumeWaitingInstanceTransactional с тем же ACK не дублирует STEP_COMPLETED")
        void duplicateAckDeliveryDoesNotDuplicateStepCompletedTrackingEvent() {
            Long sequenceId = createWaitForAckSequence("P1-8 dup ack demo");
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "ACK", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );

            NormalizedEvent ackEvent = new NormalizedEvent(
                    902L, MessageType.DOWNLINK, "ACK", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.OUT, LocalDateTime.now());

            // Первая доставка резолвит WAIT (SUCCESS -> END -> COMPLETED): STEP_COMPLETED + SEQUENCE_STOPPED.
            executionService.tryResumeWaitingInstanceTransactional(instance.getId(), ackEvent);
            ExecutionInstance afterFirst = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterFirst.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            List<TrackingEventLog> afterFirstEvents = findTrackingEvents(sequenceId);
            long stepCompletedCountAfterFirst = afterFirstEvents.stream()
                    .filter(e -> e.getEventType() == TrackingEventType.STEP_COMPLETED).count();
            assertThat(stepCompletedCountAfterFirst).isEqualTo(1);

            // Повторная доставка ТОГО ЖЕ ACK на уже COMPLETED инстанс — должна быть no-op
            // (см. tryResumeWaitingInstanceTransactional: перечитывает статус, инстанс уже не WAITING).
            executionService.tryResumeWaitingInstanceTransactional(instance.getId(), ackEvent);

            List<TrackingEventLog> afterSecondEvents = findTrackingEvents(sequenceId);
            assertThat(afterSecondEvents)
                    .as("повторная доставка на уже терминальный инстанс не должна добавить новые записи журнала")
                    .hasSameSizeAs(afterFirstEvents);
            long stepCompletedCountAfterSecond = afterSecondEvents.stream()
                    .filter(e -> e.getEventType() == TrackingEventType.STEP_COMPLETED).count();
            assertThat(stepCompletedCountAfterSecond)
                    .as("STEP_COMPLETED не должен дублироваться при идемпотентном no-op повторной доставки")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("повторная доставка одного messageId (дедуп старта, P1-7) не создаёт второй SEQUENCE_STARTED")
        void duplicateNormalizedEventDeliveryDoesNotDuplicateSequenceStartedTrackingEvent() {
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P1-8 dedup start demo", "P1-8 сценарный тест", null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Raise condition (единственный шаг)",
                    StepType.ACTION,
                    "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1-8 dedup marker\",\"alertLevel\":\"LOW\"}",
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.END, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);
            Long sequenceId = created.id();

            NormalizedEvent event = new NormalizedEvent(
                    903L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now());

            executionService.processEvent(event);
            awaitTrackingEventCount(sequenceId, TrackingEventType.SEQUENCE_STARTED, 1);

            executionService.processEvent(event);
            // даём шанс дублирующей записи проявиться, если бы дедуп не работал —
            // короткая фиксированная пауза, не замена await выше (await уже подтвердил >=1).
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            List<TrackingEventLog> events = findTrackingEvents(sequenceId);
            long startedCount = events.stream()
                    .filter(e -> e.getEventType() == TrackingEventType.SEQUENCE_STARTED).count();
            assertThat(startedCount)
                    .as("дедуп старта по triggeringMessageId (P1-7) должен предотвратить вторую запись SEQUENCE_STARTED")
                    .isEqualTo(1);
        }
    }
}
