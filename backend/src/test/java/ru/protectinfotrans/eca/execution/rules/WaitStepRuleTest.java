package ru.protectinfotrans.eca.execution.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.execution.application.CriterionEvaluator;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для WaitStepRule.
 * Проверяет логику ожидания с таймаутом и fromThisPointOnly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitStepRule")
class WaitStepRuleTest {

    @Mock
    private CriterionEvaluator criterionEvaluator;

    @InjectMocks
    private WaitStepRule rule;

    private Step step;
    private ExecutionInstance instance;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.WAIT)
                .configJson("{\"type\":\"MESSAGE_RECEIVED\"}")
                .timeoutSeconds(300)
                .build();

        LocalDateTime now = LocalDateTime.now();
        instance = ExecutionInstance.builder()
                .id(1L)
                .sequenceId(100L)
                .aircraftId("VP-BAB")
                .status(ExecutionStatus.WAITING)
                .waitStartedAt(now.minusMinutes(1))
                .waitTimeoutAt(now.plusMinutes(4))
                .build();

        context = new ExecutionContext(
                "VP-BAB",
                "SU1234",
                FlightStage.OFF,
                now,
                new HashMap<>()
        );

        rule.reset();
    }

    @Test
    @DisplayName("должен совпадать только для WAIT шагов")
    void shouldMatchOnlyWaitSteps() {
        assertThat(rule.matches(step)).isTrue();

        step.setStepType(StepType.ACTION);
        assertThat(rule.matches(step)).isFalse();

        step.setStepType(StepType.EVALUATE);
        assertThat(rule.matches(step)).isFalse();
    }

    @Test
    @DisplayName("должен вернуть SUCCESS если критерий выполнен")
    void shouldReturnSuccessWhenCriteriaMet() {
        when(criterionEvaluator.evaluate(anyString(), any(ExecutionContext.class), any(LocalDateTime.class)))
                .thenReturn(true);

        rule.execute(step, instance, context);

        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("должен вернуть FAILURE если таймаут истёк")
    void shouldReturnFailureWhenTimeoutExpired() {
        instance.setWaitTimeoutAt(LocalDateTime.now().minusMinutes(1));

        rule.execute(step, instance, context);

        assertThat(rule.getResult()).isEqualTo(StepResult.FAILURE);
    }

    @Test
    @DisplayName("должен вернуть null если критерий не выполнен и таймаут не истёк")
    void shouldReturnNullWhenWaiting() {
        when(criterionEvaluator.evaluate(anyString(), any(ExecutionContext.class), any(LocalDateTime.class)))
                .thenReturn(false);

        rule.execute(step, instance, context);

        assertThat(rule.getResult()).isNull();
        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
    }

    @Test
    @DisplayName("должен передать waitStartedAt в evaluator для fromThisPointOnly")
    void shouldPassWaitStartedAtToEvaluator() {
        LocalDateTime waitStarted = instance.getWaitStartedAt();

        when(criterionEvaluator.evaluate(anyString(), any(ExecutionContext.class), eq(waitStarted)))
                .thenReturn(false);

        rule.execute(step, instance, context);

        // Проверяем что waitStartedAt был передан
        assertThat(rule.getResult()).isNull();
    }
}
