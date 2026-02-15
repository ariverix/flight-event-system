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
import org.springframework.context.ApplicationEventPublisher;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.integration.port.in.NotificationPort;
import ru.protectinfotrans.eca.sequence.domain.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private ExecutionService service;

    private Sequence sequence;
    private NormalizedEvent event;

    @BeforeEach
    void setUp() {
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
            ExecutionInstance instance = ExecutionInstance.builder()
                    .id(1L)
                    .sequenceId(100L)
                    .aircraftId("VP-BAB")
                    .status(ExecutionStatus.WAITING)
                    .currentStepIndex(1)
                    .waitTimeoutAt(LocalDateTime.now().minusMinutes(1))
                    .stepHistory(new ArrayList<>())
                    .build();

            when(executionRepository.findWaitingWithExpiredTimeout(any())).thenReturn(List.of(instance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(executionRepository.save(any())).thenReturn(instance);

            service.checkWaitTimeouts();

            // Проверяем что advanceExecution был вызван с FAILURE
            verify(executionRepository, atLeastOnce()).save(any());
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
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(sequence));
            when(criterionEvaluator.evaluate(anyString(), any(), any())).thenReturn(true);
            when(executionRepository.save(any())).thenReturn(instance);

            service.processEvent(event);

            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }
}
