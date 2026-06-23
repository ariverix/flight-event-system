package ru.protectinfotrans.eca.eventhandling.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger;

/** Запрос на создание обработчика событий (P3-4). */
public record EventHandlerCreateRequest(

        @NotNull(message = "scope обязателен")
        HandlerScope scope,

        @NotNull(message = "scopeId обязателен")
        Long scopeId,

        @NotNull(message = "triggerType обязателен")
        NotificationTrigger triggerType,

        @NotNull(message = "channel обязателен")
        NotificationChannelType channel,

        @NotBlank(message = "target обязателен")
        @Size(max = 500, message = "target не более 500 символов")
        String target
) {}
