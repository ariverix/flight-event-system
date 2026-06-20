package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.port.out.ConditionQueryPort;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.execution.port.out.NotificationPort;
import ru.protectinfotrans.eca.execution.port.out.TrackingEventLogPort;
import ru.protectinfotrans.eca.sequence.domain.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для ExecutionService.
 * Проверяет все ветки переходов (CONTINUE, GOTO, END, ABORT) и обработку событий.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionService")
class ExecutionServiceTest {

    @Mock
    private ExecutionRepositoryPort executionRepository;

    @Mock
    private SequenceQueryPort sequenceQuery;

    @Mock
    private EcaRuleEngine ecaRuleEngine;

    @Mock
    private CriterionEvaluator criterionEvaluator;

    @Mock
    private NotificationPort notificationPort;

    @Mock
    private ConditionQueryPort conditionQueryPort;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper;

    // Реальный (не mock) кодек контекста — это чистая сериализация/десериализация без внешних
    // зависимостей кроме ObjectMapper, мокать её смысла не имеет: тесты должны видеть реальный
    // round-trip contextJson, который проверяется в интеграционных P1-3 сценарных тестах.
    @Spy
    private InstanceContextCodec instanceContextCodec = new InstanceContextCodec(new ObjectMapper());

    // P1-5: self-инъекция ExecutionService через ObjectProvider (см. javadoc поля `self` в
    // ExecutionService) — в unit-тесте нет реального Spring AOP-прокси, поэтому
    // self.getObject() стабится так, чтобы возвращать ТОТ ЖЕ service-объект, который тестируем.
    // @Transactional(REQUIRES_NEW) семантика здесь не проверяется (это unit-тест без контейнера
    // транзакций) — она покрыта интеграционным тестом single-fire на реальном Postgres.
    @Mock
    private ObjectProvider<ExecutionService> self;

    // P1-8: TrackingEventLogPort.save возвращает мок-заглушку (lenient — не все тесты доходят
    // до точки записи tracking-события, например тесты с loggingEnabled=false или ранний return).
    @Mock
    private TrackingEventLogPort trackingEventLogPort;

    @InjectMocks
    private ExecutionService service;

    private Sequence sequence;
    private NormalizedEvent event;

    @BeforeEach
    void setUp() {
        // Mock ConditionQueryPort to return empty conditions by default (lenient for tests that don't use it)
        lenient().when(conditionQueryPort.getActiveConditions(anyString())).thenReturn(Set.of());
        lenient().when(self.getObject()).thenReturn(service);

        sequence = Sequence.builder()
                .id(100L)
                .name("Test Sequence")
                .status(SequenceStatus.ACTIVE)
                .steps(new ArrayList<>())
                .build();

        // Добавим 3 шага
        Step step1 = Step.builder()
                .id(1L)
                .sequence(sequence)
                .orderIndex(1)
                .name("Step 1")
                .stepType(StepType.ACTION)
                .configJson("{}")
                .onSuccessAction(TransitionAction.CONTINUE)
                .onFailureAction(TransitionAction.ABORT)
                .build();

        Step step2 = Step.builder()
                .id(2L)
                .sequence(sequence)
                .orderIndex(2)
                .name("Step 2")
                .stepType(StepType.EVALUATE)
                .configJson("{}")
                .onSuccessAction(TransitionAction.CONTINUE)
                .onFailureAction(TransitionAction.GOTO)
                .onFailureGotoStep(1)
                .build();

        Step step3 = Step.builder()
                .id(3L)
                .sequence(sequence)
                .orderIndex(3)
                .name("Step 3")
                .stepType(StepType.ACTION)
                .configJson("{}")
                .onSuccessAction(TransitionAction.END)
                .onFailureAction(TransitionAction.END)
                .build();

        sequence.getSteps().add(step1);
        sequence.getSteps().add(step2);
        sequence.getSteps().add(step3);

        event = new NormalizedEvent(
                1L,
                ru.protectinfotrans.eca.MessageType.DOWNLINK,
                "STATUS",
                "VP-BAB",
                "SU1234",
                FlightStage.OFF,
                LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("Запуск выполнения")
    class StartExecutionTests {

        @Test
        @DisplayName("должен создать экземпляр и выполнить первый шаг")
        void shouldCreateInstanceAndExecuteFirstStep() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .flightNumber("SU1234")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(executionRepository.save(any(ExecutionInstance.class))).thenReturn(instance);
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(StepResult.SUCCESS);

            service.startExecution(100L, "VP-BAB", "SU1234");

            verify(executionRepository, atLeastOnce()).save(any(ExecutionInstance.class));
            verify(eventPublisher).publishEvent(any(ExecutionStartedEvent.class));
            verify(ecaRuleEngine, atLeastOnce()).executeStep(any(), any(), any());
        }

        @Test
        @DisplayName("должен проверить start критерии при получении события")
        void shouldCheckStartCriteriaOnEvent() {
            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));
            when(criterionEvaluator.evaluate(anyString(), any(), any())).thenReturn(true);
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();
            when(executionRepository.save(any())).thenReturn(instance);
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(null); // null = ждём

            sequence.setStartCriteriaJson("{\"type\":\"FLIGHT_STAGE\"}");

            service.processEvent(event);

            verify(executionRepository, atLeastOnce()).save(any(ExecutionInstance.class));
        }
    }

    @Nested
    @DisplayName("Переходы между шагами")
    class TransitionTests {

        private ExecutionInstance instance;

        @BeforeEach
        void setUp() {
            instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .flightNumber("SU1234")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
        }

        @Test
        @DisplayName("CONTINUE: должен перейти к следующему шагу")
        void shouldContinueToNextStep() {
            when(executionRepository.save(any())).thenReturn(instance);
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(StepResult.SUCCESS);

            Step step1 = sequence.getSteps().get(0);
            service.advanceExecution(instance, step1, StepResult.SUCCESS);

            // После перехода currentStepIndex может быть больше 2 из-за рекурсивного выполнения
            assertThat(instance.getCurrentStepIndex()).isGreaterThanOrEqualTo(2);
            verify(ecaRuleEngine, atLeastOnce()).executeStep(any(), any(), any());
        }

        @Test
        @DisplayName("GOTO: должен перейти к указанному шагу")
        void shouldGotoTargetStep() {
            instance.setCurrentStepIndex(2);
            when(executionRepository.save(any())).thenReturn(instance);
            // Вернём null для GOTO шага чтобы избежать рекурсивного выполнения
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(null);

            Step step2 = sequence.getSteps().get(1);
            service.advanceExecution(instance, step2, StepResult.FAILURE); // FAILURE → GOTO 1

            assertThat(instance.getCurrentStepIndex()).isEqualTo(1);
            verify(ecaRuleEngine, atLeastOnce()).executeStep(any(), any(), any());
        }

        @Test
        @DisplayName("END: должен завершить выполнение")
        void shouldCompleteExecution() {
            instance.setCurrentStepIndex(3);
            when(executionRepository.save(any())).thenReturn(instance);

            Step step3 = sequence.getSteps().get(2);
            service.advanceExecution(instance, step3, StepResult.SUCCESS); // SUCCESS → END

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(instance.getCompletedAt()).isNotNull();

            ArgumentCaptor<ExecutionCompletedEvent> captor = ArgumentCaptor.forClass(ExecutionCompletedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().finalStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("ABORT: должен прервать выполнение")
        void shouldAbortExecution() {
            when(executionRepository.save(any())).thenReturn(instance);

            Step step1 = sequence.getSteps().get(0);
            service.advanceExecution(instance, step1, StepResult.FAILURE); // FAILURE → ABORT

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(instance.getCompletedAt()).isNotNull();

            ArgumentCaptor<ExecutionCompletedEvent> captor = ArgumentCaptor.forClass(ExecutionCompletedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().finalStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }

    @Nested
    @DisplayName("Обработка таймаутов")
    class TimeoutTests {

        @Test
        @DisplayName("должен обработать истёкшие таймауты WAIT-шагов")
        void shouldProcessExpiredTimeouts() {
            LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1);
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .waitTimeoutAt(expiredAt)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findWaitingWithExpiredTimeout(any())).thenReturn(List.of(instance));
            // P1-5: claim должен быть вызван и подтверждён, прежде чем advanceExecution запустится
            when(executionRepository.claimExpiredTimeout(eq(1L), eq(expiredAt))).thenReturn(true);
            when(executionRepository.findById(1L)).thenReturn(Optional.of(instance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(executionRepository.save(any())).thenReturn(instance);

            service.checkWaitTimeouts();

            // claim должен быть вызван ровно один раз с правильными id/expectedTimeout
            verify(executionRepository).claimExpiredTimeout(1L, expiredAt);
            // Проверяем что advanceExecution был вызван с FAILURE
            verify(executionRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("не должен выполнять переход если claim не удался (проигран конкурентному поллеру)")
        void shouldSkipTransitionWhenClaimLost() {
            LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1);
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .waitTimeoutAt(expiredAt)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findWaitingWithExpiredTimeout(any())).thenReturn(List.of(instance));
            // claim проигран — конкурентный поллер уже забрал этот таймаут
            when(executionRepository.claimExpiredTimeout(eq(1L), eq(expiredAt))).thenReturn(false);

            service.checkWaitTimeouts();

            verify(executionRepository).claimExpiredTimeout(1L, expiredAt);
            // ни findById, ни save не должны вызываться — обработка не должна продолжаться
            verify(executionRepository, never()).findById(any());
            verify(executionRepository, never()).save(any());
            verify(sequenceQuery, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("Stop критерии")
    class StopCriteriaTests {

        @Test
        @DisplayName("должен прервать выполнение если stop критерий выполнен")
        void shouldAbortWhenStopCriteriaMet() {
            sequence.setStopCriteriaJson("{\"type\":\"FLIGHT_STAGE\"}");

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(instance));
            // P1-6: checkStopCriterionTransactional перечитывает инстанс по id в своей транзакции
            when(executionRepository.findById(1L)).thenReturn(Optional.of(instance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(criterionEvaluator.evaluate(anyString(), any(), any())).thenReturn(true);
            when(executionRepository.save(any())).thenReturn(instance);

            service.processEvent(event);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("должен игнорировать пустой stop критерий")
        void shouldSkipBlankStopCriteria() {
            sequence.setStopCriteriaJson("");

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(instance));
            when(executionRepository.findById(1L)).thenReturn(Optional.of(instance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            service.processEvent(event);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
            verify(criterionEvaluator, never()).evaluate(anyString(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить инстанс если последовательность не найдена")
        void shouldSkipWhenSequenceNotFoundForStopCriteria() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(999L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(instance));
            when(executionRepository.findById(1L)).thenReturn(Optional.of(instance));
            when(sequenceQuery.findById(999L)).thenReturn(Optional.empty());
            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of());

            service.processEvent(event);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        }
    }

    @Nested
    @DisplayName("Start критерии - детали")
    class StartCriteriaDetailTests {

        @Test
        @DisplayName("MESSAGE_RECEIVED: должен запустить выполнение при совпадении типа и шаблона")
        void shouldStartOnMessageReceivedMatch() {
            sequence.setStartCriteriaJson(
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"STATUS\"}");

            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();
            when(executionRepository.save(any())).thenReturn(instance);
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(null);

            service.processEvent(event);

            verify(executionRepository, atLeastOnce()).save(any(ExecutionInstance.class));
        }

        @Test
        @DisplayName("MESSAGE_RECEIVED: не должен запускать при несовпадении шаблона")
        void shouldNotStartOnMessageReceivedMismatch() {
            sequence.setStartCriteriaJson(
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"OTHER\"}");

            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));

            service.processEvent(event);

            verify(executionRepository, never()).save(any(ExecutionInstance.class));
        }

        @Test
        @DisplayName("должен запустить выполнение при FlightStage.INIT и отсутствии критерия")
        void shouldStartOnInitStageWithoutCriteria() {
            sequence.setStartCriteriaJson(null);
            NormalizedEvent initEvent = new NormalizedEvent(
                    1L, ru.protectinfotrans.eca.MessageType.DOWNLINK, "STATUS",
                    "VP-BAB", "SU1234", FlightStage.INIT, LocalDateTime.now());

            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();
            when(executionRepository.save(any())).thenReturn(instance);
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(null);

            service.processEvent(initEvent);

            verify(executionRepository, atLeastOnce()).save(any(ExecutionInstance.class));
        }

        @Test
        @DisplayName("не должен запускать при отсутствии критерия и стадии не INIT")
        void shouldNotStartWithoutCriteriaWhenNotInit() {
            sequence.setStartCriteriaJson(null);

            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));

            service.processEvent(event); // event имеет стадию OFF

            verify(executionRepository, never()).save(any(ExecutionInstance.class));
        }

        @Test
        @DisplayName("должен вернуть false и не запускать при некорректном JSON критерия")
        void shouldNotStartOnInvalidCriteriaJson() {
            sequence.setStartCriteriaJson("{not-valid-json");

            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of(sequence));

            service.processEvent(event);

            verify(executionRepository, never()).save(any(ExecutionInstance.class));
        }
    }

    @Nested
    @DisplayName("Возобновление WAIT-инстансов")
    class ProcessWaitingInstancesTests {

        @Test
        @DisplayName("должен возобновить WAITING инстанс при положительном результате")
        void shouldResumeWaitingInstanceOnResult() {
            ExecutionInstance waitingInstance = ExecutionInstance.builder()
                    .id(2L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(2)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(waitingInstance));
            // P1-6: tryResumeWaitingInstanceTransactional перечитывает инстанс по id в своей транзакции
            when(executionRepository.findById(2L)).thenReturn(Optional.of(waitingInstance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(StepResult.SUCCESS);
            when(executionRepository.save(any())).thenReturn(waitingInstance);

            service.processEvent(event);

            verify(ecaRuleEngine, atLeastOnce()).executeStep(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить WAITING инстанс если текущий шаг не найден")
        void shouldSkipWaitingInstanceWhenStepNotFound() {
            ExecutionInstance waitingInstance = ExecutionInstance.builder()
                    .id(2L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(99)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(waitingInstance));
            when(executionRepository.findById(2L)).thenReturn(Optional.of(waitingInstance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            service.processEvent(event);

            verify(ecaRuleEngine, never()).executeStep(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить WAITING инстанс если последовательность не найдена")
        void shouldSkipWaitingInstanceWhenSequenceNotFound() {
            ExecutionInstance waitingInstance = ExecutionInstance.builder()
                    .id(2L)
                    .sequenceId(999L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findActiveByAircraftId("VP-BAB")).thenReturn(List.of(waitingInstance));
            when(executionRepository.findById(2L)).thenReturn(Optional.of(waitingInstance));
            when(sequenceQuery.findById(999L)).thenReturn(Optional.empty());
            when(sequenceQuery.findAllByStatus(SequenceStatus.ACTIVE)).thenReturn(List.of());

            service.processEvent(event);

            verify(ecaRuleEngine, never()).executeStep(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Уведомления и невалидный GOTO")
    class NotifyAndInvalidGotoTests {

        private ExecutionInstance instance;

        @BeforeEach
        void setUp() {
            instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .flightNumber("SU1234")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(2)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
        }

        @Test
        @DisplayName("должен отправить уведомление при onSuccessNotify=true")
        void shouldNotifyOnSuccess() {
            Step notifyStep = Step.builder()
                    .id(10L)
                    .sequence(sequence)
                    .orderIndex(2)
                    .name("Notify step")
                    .stepType(StepType.ACTION)
                    .configJson("{}")
                    .onSuccessAction(TransitionAction.END)
                    .onSuccessNotify(true)
                    .onFailureAction(TransitionAction.ABORT)
                    .build();

            when(executionRepository.save(any())).thenReturn(instance);

            service.advanceExecution(instance, notifyStep, StepResult.SUCCESS);

            verify(notificationPort).notifyStepResult(
                    eq(1L), eq(2), eq("SUCCESS"), eq("VP-BAB"), anyString());
        }

        @Test
        @DisplayName("должен прервать выполнение при невалидном GOTO (вне диапазона)")
        void shouldAbortOnInvalidGotoTarget() {
            Step gotoStep = Step.builder()
                    .id(11L)
                    .sequence(sequence)
                    .orderIndex(2)
                    .name("Invalid goto")
                    .stepType(StepType.ACTION)
                    .configJson("{}")
                    .onSuccessAction(TransitionAction.GOTO)
                    .onSuccessGotoStep(999)
                    .onFailureAction(TransitionAction.ABORT)
                    .build();

            when(executionRepository.save(any())).thenReturn(instance);

            service.advanceExecution(instance, gotoStep, StepResult.SUCCESS);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("должен прервать выполнение при GOTO с null target")
        void shouldAbortOnNullGotoTarget() {
            Step gotoStep = Step.builder()
                    .id(12L)
                    .sequence(sequence)
                    .orderIndex(2)
                    .name("Null goto")
                    .stepType(StepType.ACTION)
                    .configJson("{}")
                    .onSuccessAction(TransitionAction.GOTO)
                    .onSuccessGotoStep(null)
                    .onFailureAction(TransitionAction.ABORT)
                    .build();

            when(executionRepository.save(any())).thenReturn(instance);

            service.advanceExecution(instance, gotoStep, StepResult.SUCCESS);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }

    @Nested
    @DisplayName("startExecution - граничные случаи")
    class StartExecutionEdgeCases {

        @Test
        @DisplayName("должен бросить исключение если последовательность не найдена")
        void shouldThrowWhenSequenceNotFound() {
            when(sequenceQuery.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.startExecution(999L, "VP-BAB", "SU1234"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("не должен создавать инстанс если у последовательности нет шагов")
        void shouldNotStartWhenNoSteps() {
            Sequence emptySequence = Sequence.builder()
                    .id(200L)
                    .name("Empty")
                    .status(SequenceStatus.ACTIVE)
                    .steps(new ArrayList<>())
                    .build();
            when(sequenceQuery.findById(200L)).thenReturn(Optional.of(emptySequence));

            service.startExecution(200L, "VP-BAB", "SU1234");

            verify(executionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Защита от бесконечного синхронного цикла (P1-2)")
    class InfiniteLoopProtectionUnitTests {

        @Test
        @DisplayName("цикл CONTINUE/GOTO без WAIT должен быть прерван MAX_SYNCHRONOUS_TRANSITIONS и завершиться ABORTED")
        void infiniteLoopIsAbortedByTransitionLimit() {
            // Двухшаговый цикл: step1 CONTINUE -> step2, step2 onSuccess GOTO -> step1, навечно.
            Sequence loopSequence = Sequence.builder()
                    .id(200L)
                    .name("Loop sequence")
                    .status(SequenceStatus.ACTIVE)
                    .steps(new ArrayList<>())
                    .build();

            Step loopStep1 = Step.builder()
                    .id(10L).sequence(loopSequence).orderIndex(1).name("Loop1")
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.CONTINUE)
                    .onFailureAction(TransitionAction.CONTINUE)
                    .build();
            Step loopStep2 = Step.builder()
                    .id(11L).sequence(loopSequence).orderIndex(2).name("Loop2")
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.GOTO).onSuccessGotoStep(1)
                    .onFailureAction(TransitionAction.GOTO).onFailureGotoStep(1)
                    .build();
            loopSequence.getSteps().add(loopStep1);
            loopSequence.getSteps().add(loopStep2);

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(5L)
                    .sequenceId(200L)
                    .aircraftId("VP-LOOP")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(200L)).thenReturn(Optional.of(loopSequence));
            when(executionRepository.save(any())).thenReturn(instance);
            // ACTION всегда SUCCESS — цикл никогда не разрывается сам по себе
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(StepResult.SUCCESS);

            service.advanceExecution(instance, loopStep1, StepResult.SUCCESS);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(instance.getCompletedAt()).isNotNull();
            // история должна быть конечной и не превышать лимит+1 (исходный шаг + лимит переходов)
            assertThat(instance.getStepHistory().size())
                    .isLessThanOrEqualTo(ExecutionService.MAX_SYNCHRONOUS_TRANSITIONS + 1);
            assertThat(instance.getStepHistory().size()).isGreaterThan(10);
        }
    }

    @Nested
    @DisplayName("Сброс waitStartedAt/waitTimeoutAt при выходе из WAIT-шага (P1-2, GOTO назад)")
    class WaitTimeoutResetUnitTests {

        @Test
        @DisplayName("выход из WAIT-шага (FAILURE по таймауту) должен сбросить waitStartedAt/waitTimeoutAt")
        void leavingWaitStepClearsTimeoutFields() {
            Step waitStep = Step.builder()
                    .id(20L).sequence(sequence).orderIndex(1).name("Wait step")
                    .stepType(StepType.WAIT).configJson("{}").timeoutSeconds(60)
                    .onSuccessAction(TransitionAction.CONTINUE)
                    .onFailureAction(TransitionAction.ABORT)
                    .build();

            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .waitStartedAt(LocalDateTime.now().minusMinutes(2))
                    .waitTimeoutAt(LocalDateTime.now().minusMinutes(1))
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(executionRepository.save(any())).thenReturn(instance);

            service.advanceExecution(instance, waitStep, StepResult.FAILURE); // FAILURE -> ABORT

            assertThat(instance.getWaitStartedAt()).isNull();
            assertThat(instance.getWaitTimeoutAt()).isNull();
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("выход из ACTION-шага (WAIT_TIME) НЕ должен сбрасывать waitStartedAt/waitTimeoutAt")
        void leavingActionStepKeepsTimeoutFields() {
            Step actionStep = Step.builder()
                    .id(21L).sequence(sequence).orderIndex(1).name("Action wait_time")
                    .stepType(StepType.ACTION).configJson("{\"actionType\":\"WAIT_TIME\"}")
                    .onSuccessAction(TransitionAction.END)
                    .onFailureAction(TransitionAction.END)
                    .build();

            LocalDateTime waitStart = LocalDateTime.now().minusMinutes(1);
            LocalDateTime waitTimeout = LocalDateTime.now().plusMinutes(1);
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .waitStartedAt(waitStart)
                    .waitTimeoutAt(waitTimeout)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(executionRepository.save(any())).thenReturn(instance);

            service.advanceExecution(instance, actionStep, StepResult.SUCCESS); // SUCCESS -> END

            // ACTION WAIT_TIME хранит здесь метаданные о вычисленной паузе — это не активное
            // ожидание критерия WAIT-шага, поэтому поля не сбрасываются при переходе.
            assertThat(instance.getWaitStartedAt()).isEqualTo(waitStart);
            assertThat(instance.getWaitTimeoutAt()).isEqualTo(waitTimeout);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("resumeRunningInstanceAfterRestart - P1-4 resume RUNNING-инстансов")
    class ResumeRunningInstanceAfterRestartTests {

        @Test
        @DisplayName("должен повторно выполнить текущий шаг и продвинуть инстанс при детерминированном результате")
        void shouldReExecuteCurrentStepAndAdvance() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(7L)
                    .sequenceId(100L)
                    .aircraftId("VP-BQR")
                    .flightNumber("SU1234")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(StepResult.SUCCESS);
            when(executionRepository.save(any())).thenReturn(instance);

            service.resumeRunningInstanceAfterRestart(instance);

            verify(ecaRuleEngine, atLeastOnce()).executeStep(any(), any(), any());
            // step1 onSuccess=CONTINUE -> currentStepIndex продвинулся за пределы исходного значения
            assertThat(instance.getCurrentStepIndex()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("должен сохранить инстанс как WAITING если повторный шаг ещё не resolved (null результат)")
        void shouldPersistWaitingStateWhenStepNotYetResolved() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(8L)
                    .sequenceId(100L)
                    .aircraftId("VP-BQR")
                    .flightNumber("SU1234")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(ecaRuleEngine.executeStep(any(), any(), any())).thenReturn(null);
            when(executionRepository.save(any())).thenReturn(instance);

            service.resumeRunningInstanceAfterRestart(instance);

            verify(executionRepository).save(instance);
            verify(eventPublisher, never()).publishEvent(any(ExecutionCompletedEvent.class));
        }

        @Test
        @DisplayName("должен оставить инстанс как есть если последовательность не найдена")
        void shouldLeaveInstanceUntouchedWhenSequenceNotFound() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(9L)
                    .sequenceId(999L)
                    .aircraftId("VP-BQR")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(999L)).thenReturn(Optional.empty());

            service.resumeRunningInstanceAfterRestart(instance);

            verify(ecaRuleEngine, never()).executeStep(any(), any(), any());
            verify(executionRepository, never()).save(any());
        }

        @Test
        @DisplayName("должен оставить инстанс как есть если текущий шаг не найден в последовательности")
        void shouldLeaveInstanceUntouchedWhenStepNotFound() {
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(10L)
                    .sequenceId(100L)
                    .aircraftId("VP-BQR")
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(99)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));

            service.resumeRunningInstanceAfterRestart(instance);

            verify(ecaRuleEngine, never()).executeStep(any(), any(), any());
            verify(executionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("checkWaitTimeouts - граничные случаи")
    class CheckWaitTimeoutsEdgeCases {

        @Test
        @DisplayName("должен пропустить просроченный инстанс если последовательность не найдена")
        void shouldSkipExpiredInstanceWhenSequenceNotFound() {
            LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1);
            ExecutionInstance expired = ExecutionInstance.builder()
                    .id(5L)
                    .sequenceId(999L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .waitTimeoutAt(expiredAt)
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findWaitingWithExpiredTimeout(any())).thenReturn(List.of(expired));
            when(executionRepository.claimExpiredTimeout(eq(5L), eq(expiredAt))).thenReturn(true);
            when(executionRepository.findById(5L)).thenReturn(Optional.of(expired));
            when(sequenceQuery.findById(999L)).thenReturn(Optional.empty());

            service.checkWaitTimeouts();

            verify(executionRepository, never()).save(any());
        }

        @Test
        @DisplayName("не должен делать ничего если нет просроченных инстансов")
        void shouldDoNothingWhenNoExpiredInstances() {
            when(executionRepository.findWaitingWithExpiredTimeout(any())).thenReturn(List.of());

            service.checkWaitTimeouts();

            verify(executionRepository, never()).save(any());
        }
    }
}
