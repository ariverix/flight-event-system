package ru.protectinfotrans.eca.execution.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.rules.ActionStepRule;
import ru.protectinfotrans.eca.execution.rules.EvaluateStepRule;
import ru.protectinfotrans.eca.execution.rules.WaitStepRule;
import ru.protectinfotrans.eca.sequence.domain.Step;

/** Запускает нужное Easy Rules правило для каждого типа шага. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EcaRuleEngine {

    // prototype scope — каждый вызов должен получать чистый экземпляр без state от предыдущего
    private final ObjectProvider<ActionStepRule> actionStepRuleProvider;
    private final ObjectProvider<EvaluateStepRule> evaluateStepRuleProvider;
    private final ObjectProvider<WaitStepRule> waitStepRuleProvider;

    /** @return SUCCESS/FAILURE или null если WAIT ещё ждёт */
    public StepResult executeStep(Step step, ExecutionInstance instance, ExecutionContext context) {
        log.debug("Executing step {} (type: {}) for instance {}", step.getOrderIndex(), step.getStepType(), instance.getId());

        ActionStepRule actionStepRule = actionStepRuleProvider.getObject();
        EvaluateStepRule evaluateStepRule = evaluateStepRuleProvider.getObject();
        WaitStepRule waitStepRule = waitStepRuleProvider.getObject();

        Facts facts = new Facts();
        facts.put("step", step);
        facts.put("instance", instance);
        facts.put("context", context);

        Rules rules = new Rules();
        rules.register(actionStepRule);
        rules.register(evaluateStepRule);
        rules.register(waitStepRule);

        DefaultRulesEngine rulesEngine = new DefaultRulesEngine();
        // fire() запустит ровно одно правило — @Condition взаимоисключающие по StepType
        rulesEngine.fire(rules, facts);

        // читаем результат только из того правила чей @Condition сработал,
        // остальные два остались с result=null
        StepResult result = switch (step.getStepType()) {
            case ACTION -> actionStepRule.getResult();
            case EVALUATE -> evaluateStepRule.getResult();
            case WAIT -> waitStepRule.getResult();
        };

        log.debug("Step {} execution result: {}", step.getOrderIndex(), result);
        return result;
    }
}
