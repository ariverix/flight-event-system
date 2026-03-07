package ru.protectinfotrans.eca.execution.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.rules.ActionStepRule;
import ru.protectinfotrans.eca.execution.rules.EvaluateStepRule;
import ru.protectinfotrans.eca.execution.rules.WaitStepRule;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты оркестратора ECA-правил.
 * Проверяет делегирование к ActionStepRule, EvaluateStepRule, WaitStepRule.
 *
 * См. диплом: раздел 1.3.3 (ECA модель), таблица 1.3, Глава 3 (Тестирование)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EcaRuleEngine")
class EcaRuleEngineTest {

    @Mock
    private ActionStepRule actionStepRule;

    @Mock
    private EvaluateStepRule evaluateStepRule;

    @Mock
    private WaitStepRule waitStepRule;

    @InjectMocks
    private EcaRuleEngine engine;

    private ExecutionInstance instance;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        instance = ExecutionInstance.builder()
                .id(1L)
                .sequenceId(100L)
                .aircraftId("VP-BQR")
                .status(ExecutionStatus.RUNNING)
                .build();

        context = new ExecutionContext(
                "VP-BQR",
                "SU1234",
                FlightStage.OFF,
                LocalDateTime.now(),
                new HashMap<>()
        );
    }

    @Test
    @DisplayName("ACTION шаг: должен выполнить ActionStepRule и вернуть SUCCESS")
    void shouldExecuteActionStepAndReturnSuccess() {
        Step step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.ACTION)
                .configJson("{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"TEST\"}")
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(true);
        when(evaluateStepRule.matches(step)).thenReturn(false);
        when(waitStepRule.matches(step)).thenReturn(false);
        when(actionStepRule.getResult()).thenReturn(StepResult.SUCCESS);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("ACTION шаг: должен вернуть FAILURE при неудаче действия")
    void shouldReturnFailureForActionStep() {
        Step step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.ACTION)
                .configJson("{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"TEST\"}")
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(true);
        when(evaluateStepRule.matches(step)).thenReturn(false);
        when(waitStepRule.matches(step)).thenReturn(false);
        when(actionStepRule.getResult()).thenReturn(StepResult.FAILURE);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isEqualTo(StepResult.FAILURE);
    }

    @Test
    @DisplayName("EVALUATE шаг: должен вернуть SUCCESS если критерий истинен")
    void shouldReturnSuccessForEvaluateWhenCriteriaMet() {
        Step step = Step.builder()
                .orderIndex(2)
                .stepType(StepType.EVALUATE)
                .configJson("{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"OFF\"}")
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.matches(step)).thenReturn(true);
        when(waitStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.getResult()).thenReturn(StepResult.SUCCESS);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("EVALUATE шаг: должен вернуть FAILURE если критерий ложен")
    void shouldReturnFailureForEvaluateWhenCriteriaNotMet() {
        Step step = Step.builder()
                .orderIndex(2)
                .stepType(StepType.EVALUATE)
                .configJson("{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"IN\"}")
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.matches(step)).thenReturn(true);
        when(waitStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.getResult()).thenReturn(StepResult.FAILURE);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isEqualTo(StepResult.FAILURE);
    }

    @Test
    @DisplayName("WAIT шаг: должен вернуть null пока условие не выполнено")
    void shouldReturnNullForWaitStepWhenWaiting() {
        Step step = Step.builder()
                .orderIndex(3)
                .stepType(StepType.WAIT)
                .configJson("{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"POS\"}")
                .timeoutSeconds(300)
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.matches(step)).thenReturn(false);
        when(waitStepRule.matches(step)).thenReturn(true);
        when(waitStepRule.getResult()).thenReturn(null);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("WAIT шаг: должен вернуть SUCCESS когда условие выполнено")
    void shouldReturnSuccessForWaitStepWhenCriteriaMet() {
        Step step = Step.builder()
                .orderIndex(3)
                .stepType(StepType.WAIT)
                .configJson("{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"POS\"}")
                .timeoutSeconds(300)
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(false);
        when(evaluateStepRule.matches(step)).thenReturn(false);
        when(waitStepRule.matches(step)).thenReturn(true);
        when(waitStepRule.getResult()).thenReturn(StepResult.SUCCESS);

        StepResult result = engine.executeStep(step, instance, context);

        assertThat(result).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("должен сбрасывать состояние правил перед каждым выполнением")
    void shouldResetAllRulesBeforeExecution() {
        Step step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.ACTION)
                .configJson("{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"TEST\"}")
                .build();

        doNothing().when(actionStepRule).reset();
        doNothing().when(evaluateStepRule).reset();
        doNothing().when(waitStepRule).reset();
        when(actionStepRule.matches(step)).thenReturn(true);
        when(actionStepRule.getResult()).thenReturn(StepResult.SUCCESS);

        engine.executeStep(step, instance, context);

        verify(actionStepRule).reset();
        verify(evaluateStepRule).reset();
        verify(waitStepRule).reset();
    }
}
