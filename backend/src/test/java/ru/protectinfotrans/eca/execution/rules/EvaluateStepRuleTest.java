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
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

import java.time.LocalDateTime;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для EvaluateStepRule.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluateStepRule")
class EvaluateStepRuleTest {

    @Mock
    private CriterionEvaluator criterionEvaluator;

    @InjectMocks
    private EvaluateStepRule rule;

    private Step step;
    private ExecutionContext context;
    private ExecutionInstance instance;

    @BeforeEach
    void setUp() {
        step = Step.builder()
                .orderIndex(1)
                .stepType(StepType.EVALUATE)
                .configJson("{\"type\":\"FLIGHT_STAGE\"}")
                .build();

        context = new ExecutionContext(
                "VP-BAB",
                "SU1234",
                FlightStage.OFF,
                LocalDateTime.now(),
                new HashMap<>()
        );

        instance = ExecutionInstance.builder().build();

        rule.reset();
    }

    @Test
    @DisplayName("должен совпадать только для EVALUATE шагов")
    void shouldMatchOnlyEvaluateSteps() {
        assertThat(rule.matches(step)).isTrue();

        step.setStepType(StepType.ACTION);
        assertThat(rule.matches(step)).isFalse();

        step.setStepType(StepType.WAIT);
        assertThat(rule.matches(step)).isFalse();
    }

    @Test
    @DisplayName("должен вернуть SUCCESS если критерий выполнен")
    void shouldReturnSuccessWhenCriteriaMet() {
        when(criterionEvaluator.evaluate(anyString(), any(ExecutionContext.class), isNull()))
                .thenReturn(true);

        rule.execute(step, instance, context);

        verify(criterionEvaluator).evaluate(step.getConfigJson(), context, null);
        assertThat(rule.getResult()).isEqualTo(StepResult.SUCCESS);
    }

    @Test
    @DisplayName("должен вернуть FAILURE если критерий не выполнен")
    void shouldReturnFailureWhenCriteriaNotMet() {
        when(criterionEvaluator.evaluate(anyString(), any(ExecutionContext.class), isNull()))
                .thenReturn(false);

        rule.execute(step, instance, context);

        assertThat(rule.getResult()).isEqualTo(StepResult.FAILURE);
    }
}
