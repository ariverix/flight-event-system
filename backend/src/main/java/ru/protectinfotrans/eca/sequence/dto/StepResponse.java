package ru.protectinfotrans.eca.sequence.dto;

import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

/**
 * Ответ с данными шага последовательности.
 * См. диплом: раздел 1.2.2 (Sequencer — 3 типа шагов)
 */
public record StepResponse(
        Long id,
        Integer orderIndex,
        String name,
        StepType stepType,
        String configJson,
        Integer timeoutSeconds,
        TransitionAction onSuccessAction,
        Integer onSuccessGotoStep,
        Boolean onSuccessNotify,
        TransitionAction onFailureAction,
        Integer onFailureGotoStep,
        Boolean onFailureNotify
) {}
