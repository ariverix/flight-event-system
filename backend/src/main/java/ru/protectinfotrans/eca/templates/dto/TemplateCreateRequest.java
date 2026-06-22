package ru.protectinfotrans.eca.templates.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

/**
 * Запрос на создание шаблона. {@code origin} обязателен для {@code UPLINK}/{@code GROUND} и
 * должен быть {@code null} для {@code DOWNLINK} — проверяется на уровне сервиса
 * ({@code TemplateValidator}), не в DTO-аннотациях (правило зависит от значения другого поля).
 */
public record TemplateCreateRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        MessageType messageType,

        UplinkOrigin origin,

        @Size(max = 100)
        String category,

        @NotBlank
        String body,

        Boolean active
) {
}
