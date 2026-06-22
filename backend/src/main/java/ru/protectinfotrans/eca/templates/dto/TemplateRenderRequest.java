package ru.protectinfotrans.eca.templates.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** Запрос на пробный рендеринг шаблона по имени — используется в UI для предпросмотра. */
public record TemplateRenderRequest(
        @NotBlank
        String templateName,
        Map<String, Object> variables
) {
}
