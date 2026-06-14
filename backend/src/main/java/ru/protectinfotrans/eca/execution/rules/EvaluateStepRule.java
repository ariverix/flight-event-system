package ru.protectinfotrans.eca.execution.rules;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.annotation.Action;
import org.jeasy.rules.annotation.Condition;
import org.jeasy.rules.annotation.Fact;
import org.jeasy.rules.annotation.Rule;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.application.CriterionEvaluator;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

/** Правило для EVALUATE-шагов — проверяет условие прямо сейчас. */
@Rule(name = "EvaluateStepRule", description = "Evaluates EVALUATE IF steps")
@Component
@org.springframework.context.annotation.Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class EvaluateStepRule {

    private final CriterionEvaluator criterionEvaluator;

    private StepResult result;

    @Condition
    public boolean matches(@Fact("step") Step step) {
        return step.getStepType() == StepType.EVALUATE;
    }

    @Action
    public void execute(
            @Fact("step") Step step,
            @Fact("instance") ExecutionInstance instance,
            @Fact("context") ExecutionContext context
    ) {
        try {
            // waitStartedAt предыдущего WAIT-шага — для fromThisPointOnly в EVALUATE,
            // иначе MESSAGE_RECEIVED находит сообщения из прошлых выполнений
            boolean evaluationResult = criterionEvaluator.evaluate(step.getConfigJson(), context, instance.getWaitStartedAt());

            result = evaluationResult ? StepResult.SUCCESS : StepResult.FAILURE;
            log.debug("EvaluateStepRule: criteria evaluated to {}", result);

        } catch (Exception e) {
            log.error("EvaluateStepRule execution failed", e);
            result = StepResult.FAILURE;
        }
    }

    public StepResult getResult() {
        return result;
    }

    public void reset() {
        this.result = null;
    }
}
