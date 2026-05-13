package ru.protectinfotrans.eca.sequence.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.sequence.domain.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты валидатора последовательностей.
 *
 * См. диплом: раздел 1.3.5 (UC-04 Активировать последовательность)
 */
class SequenceValidatorTest {

    private final SequenceValidator validator = new SequenceValidator();

    @Test
    @DisplayName("Валидная последовательность проходит без ошибок")
    void shouldPassValidSequence() {
        Sequence seq = buildSequence(
                buildStep(1, "Action", StepType.ACTION, null,
                        TransitionAction.END, null, TransitionAction.ABORT, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Пустая последовательность — ошибка")
    void shouldFailOnEmptySteps() {
        Sequence seq = Sequence.builder().build();
        seq.setSteps(new ArrayList<>());

        List<String> errors = validator.validate(seq);

        assertThat(errors).anyMatch(e -> e.contains("минимум 1 шаг"));
    }

    @Test
    @DisplayName("WAIT-шаг без таймаута — ошибка")
    void shouldFailOnWaitWithoutTimeout() {
        Sequence seq = buildSequence(
                buildStep(1, "Wait", StepType.WAIT, null,
                        TransitionAction.END, null, TransitionAction.ABORT, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).anyMatch(e -> e.contains("таймаут"));
    }

    @Test
    @DisplayName("WAIT-шаг с таймаутом — ок")
    void shouldPassWaitWithTimeout() {
        Sequence seq = buildSequence(
                buildStep(1, "Wait", StepType.WAIT, 60,
                        TransitionAction.END, null, TransitionAction.ABORT, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("GOTO на несуществующий шаг — ошибка")
    void shouldFailOnGotoInvalidStep() {
        Sequence seq = buildSequence(
                buildStep(1, "Step1", StepType.ACTION, null,
                        TransitionAction.GOTO, 99, TransitionAction.END, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).anyMatch(e -> e.contains("несуществующий шаг"));
    }

    @Test
    @DisplayName("GOTO на существующий шаг — ок")
    void shouldPassGotoValidStep() {
        Sequence seq = buildSequence(
                buildStep(1, "Step1", StepType.ACTION, null,
                        TransitionAction.GOTO, 2, TransitionAction.END, null),
                buildStep(2, "Step2", StepType.ACTION, null,
                        TransitionAction.END, null, TransitionAction.ABORT, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Ни один шаг не ведёт к END — ошибка")
    void shouldFailOnNoEndPath() {
        Sequence seq = buildSequence(
                buildStep(1, "Step1", StepType.ACTION, null,
                        TransitionAction.CONTINUE, null, TransitionAction.ABORT, null)
        );

        List<String> errors = validator.validate(seq);

        assertThat(errors).anyMatch(e -> e.contains("END"));
    }

    // --- Хелперы ---

    private Sequence buildSequence(Step... steps) {
        Sequence seq = Sequence.builder().build();
        seq.setSteps(new ArrayList<>(List.of(steps)));
        return seq;
    }

    private Step buildStep(int orderIndex, String name, StepType type, Integer timeout,
                           TransitionAction onSuccess, Integer successGoto,
                           TransitionAction onFailure, Integer failureGoto) {
        return Step.builder()
                .orderIndex(orderIndex)
                .name(name)
                .stepType(type)
                .timeoutSeconds(timeout)
                .configJson("{}")
                .onSuccessAction(onSuccess)
                .onSuccessGotoStep(successGoto)
                .onSuccessNotify(false)
                .onFailureAction(onFailure)
                .onFailureGotoStep(failureGoto)
                .onFailureNotify(false)
                .build();
    }
}
