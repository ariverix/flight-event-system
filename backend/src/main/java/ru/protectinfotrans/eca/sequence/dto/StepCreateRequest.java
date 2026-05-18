package ru.protectinfotrans.eca.sequence.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

/**
 * Запрос на добавление шага в последовательность.
 */
public record StepCreateRequest(

        @Size(max = 100, message = "Наименование шага не более 100 символов")
        String name,

        @NotNull(message = "Тип шага обязателен")
        StepType stepType,

        @NotNull(message = "Конфигурация шага обязательна")
        String configJson,

        Integer timeoutSeconds,

        @NotNull(message = "Действие при успехе обязательно")
        TransitionAction onSuccessAction,

        Integer onSuccessGotoStep,

        Boolean onSuccessNotify,

        @NotNull(message = "Действие при неуспехе обязательно")
        TransitionAction onFailureAction,

        Integer onFailureGotoStep,

        Boolean onFailureNotify
) {}
