package ru.protectinfotrans.eca.sequence.application;

import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Валидатор последовательности перед активацией.
 * Проверяет структурную корректность: наличие шагов, корректность GOTO-ссылок,
 * достижимость END, наличие таймаутов у WAIT-шагов.
 *
 * См. диплом: раздел 1.3.5 (UC-04 Активировать последовательность)
 */
@Component
public class SequenceValidator {

    /**
     * Валидирует последовательность перед активацией.
     *
     * @param sequence последовательность для валидации
     * @return список ошибок (пустой если валидация пройдена)
     */
    public List<String> validate(Sequence sequence) {
        List<String> errors = new ArrayList<>();
        List<Step> steps = sequence.getSteps();

        if (steps.isEmpty()) {
            errors.add("Последовательность должна содержать минимум 1 шаг");
            return errors;
        }

        Set<Integer> validIndexes = new HashSet<>();
        for (Step step : steps) {
            validIndexes.add(step.getOrderIndex());
        }

        boolean hasEndPath = false;

        for (Step step : steps) {
            // WAIT-шаги обязаны иметь таймаут > 0
            if (step.getStepType() == StepType.WAIT) {
                if (step.getTimeoutSeconds() == null || step.getTimeoutSeconds() <= 0) {
                    errors.add("Шаг «" + step.getName() + "» (WAIT) должен иметь таймаут > 0");
                }
            }

            // Проверка GOTO-ссылок на SUCCESS
            if (step.getOnSuccessAction() == TransitionAction.GOTO) {
                if (step.getOnSuccessGotoStep() == null
                        || !validIndexes.contains(step.getOnSuccessGotoStep())) {
                    errors.add("Шаг «" + step.getName()
                            + "»: onSuccessGotoStep указывает на несуществующий шаг");
                }
            }

            // Проверка GOTO-ссылок на FAILURE
            if (step.getOnFailureAction() == TransitionAction.GOTO) {
                if (step.getOnFailureGotoStep() == null
                        || !validIndexes.contains(step.getOnFailureGotoStep())) {
                    errors.add("Шаг «" + step.getName()
                            + "»: onFailureGotoStep указывает на несуществующий шаг");
                }
            }

            // Проверка наличия хотя бы одного пути к END
            if (step.getOnSuccessAction() == TransitionAction.END
                    || step.getOnFailureAction() == TransitionAction.END) {
                hasEndPath = true;
            }
        }

        if (!hasEndPath) {
            errors.add("Ни один шаг не ведёт к END — последовательность не сможет завершиться");
        }

        return errors;
    }
}
