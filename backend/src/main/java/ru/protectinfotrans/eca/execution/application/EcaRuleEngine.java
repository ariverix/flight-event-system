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

/**
 * Оркестратор ECA-правил. Координирует выполнение шагов через Easy Rules.
 *
 * См. диплом: раздел 1.3.3, таблица 1.3 — соответствие ECA и Easy Rules
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EcaRuleEngine {

    // ObjectProvider используется т.к. правила — prototype-scoped бины:
    // каждый вызов executeStep создаёт свежие экземпляры без общего state
    private final ObjectProvider<ActionStepRule> actionStepRuleProvider;
    private final ObjectProvider<EvaluateStepRule> evaluateStepRuleProvider;
    private final ObjectProvider<WaitStepRule> waitStepRuleProvider;

    /**
     * Выполнить шаг последовательности.
     *
     * @param step шаг для выполнения
     * @param instance экземпляр выполнения
     * @param context контекст выполнения
     * @return результат выполнения (SUCCESS/FAILURE) или null для WAIT (продолжаем ждать)
     */
    public StepResult executeStep(Step step, ExecutionInstance instance, ExecutionContext context) {
        log.debug("Executing step {} (type: {}) for instance {}", step.getOrderIndex(), step.getStepType(), instance.getId());

        // Получаем свежие prototype-экземпляры правил — нет shared state между вызовами
        ActionStepRule actionStepRule = actionStepRuleProvider.getObject();
        EvaluateStepRule evaluateStepRule = evaluateStepRuleProvider.getObject();
        WaitStepRule waitStepRule = waitStepRuleProvider.getObject();

        // Создание фактов для Easy Rules
        Facts facts = new Facts();
        facts.put("step", step);
        facts.put("instance", instance);
        facts.put("context", context);

        // Создание набора правил
        Rules rules = new Rules();
        rules.register(actionStepRule);
        rules.register(evaluateStepRule);
        rules.register(waitStepRule);

        // Выполнение правил
        DefaultRulesEngine rulesEngine = new DefaultRulesEngine();
        rulesEngine.fire(rules, facts);

        // Получение результата из соответствующего правила
        StepResult result = switch (step.getStepType()) {
            case ACTION -> actionStepRule.getResult();
            case EVALUATE -> evaluateStepRule.getResult();
            case WAIT -> waitStepRule.getResult();
        };

        log.debug("Step {} execution result: {}", step.getOrderIndex(), result);
        return result;
    }
}
