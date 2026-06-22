package ru.protectinfotrans.eca.templates.dto;

/** Результат рендеринга шаблона — готовый текст для отправки в канал (uplink/ground). */
public record TemplateRenderResponse(
        String templateName,
        String renderedText
) {
}
