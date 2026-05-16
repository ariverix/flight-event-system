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
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

import java.time.LocalDateTime;

/**
 * Easy Rules правило для шагов типа WAIT.
 * Проверяет критерий с учётом таймаута и fromThisPointOnly.
 *
 * Логика:
 * - Если критерий выполнен → SUCCESS
 * - Если таймаут истёк → FAILURE
 * - Иначе → null (продолжаем ждать, статус WAITING)
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Wait FOR), раздел 1.3.3 (ECA модель), таблица 1.3
 */
@Rule(name = "WaitStepRule", description = "Executes WAIT FOR steps")
@Component
@RequiredArgsConstructor
@Slf4j
public class WaitStepRule {

    private final CriterionEvaluator criterionEvaluator;

    private StepResult result;

    @Condition
    public boolean matches(@Fact("step") Step step) {
        return step.getStepType() == StepType.WAIT;
    }

    @Action
    public void execute(
            @Fact("step") Step step,
            @Fact("instance") ExecutionInstance instance,
            @Fact("context") ExecutionContext context
    ) {
        try {
            LocalDateTime now = context.currentTime();

            // Проверяем таймаут
            if (instance.getWaitTimeoutAt() != null && now.isAfter(instance.getWaitTimeoutAt())) {
                log.info("WaitStepRule: timeout expired at {}", instance.getWaitTimeoutAt());
                result = StepResult.FAILURE;
                return;
            }

            // Проверяем критерий с учётом fromThisPointOnly
            // waitStartedAt передаётся в CriterionEvaluator для MESSAGE_RECEIVED
            boolean criterionMet = criterionEvaluator.evaluate(
                    step.getConfigJson(),
                    context,
                    instance.getWaitStartedAt()
            );

            if (criterionMet) {
                log.info("WaitStepRule: criterion met");
                result = StepResult.SUCCESS;
            } else {
                log.debug("WaitStepRule: criterion not met yet, continue waiting");
                result = null;
                instance.setStatus(ExecutionStatus.WAITING);
                // Инициализируем таймаут при первом входе в WAITING
                if (instance.getWaitTimeoutAt() == null && step.getTimeoutSeconds() != null) {
                    instance.setWaitStartedAt(now);
                    instance.setWaitTimeoutAt(now.plusSeconds(step.getTimeoutSeconds()));
                    log.info("WaitStepRule: timeout set to {} sec, expires at {}",
                            step.getTimeoutSeconds(), instance.getWaitTimeoutAt());
                }
            }

        } catch (Exception e) {
            log.error("WaitStepRule execution failed", e);
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
