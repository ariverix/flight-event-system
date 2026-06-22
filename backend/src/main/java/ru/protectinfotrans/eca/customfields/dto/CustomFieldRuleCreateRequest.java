package ru.protectinfotrans.eca.customfields.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;

/**
 * Запрос на создание правила извлечения custom field. {@code pattern} должен быть валидным regex
 * с РОВНО ОДНОЙ capturing-группой для {@code extractionSource == CONTENT} — проверяется на уровне
 * сервиса ({@code CustomFieldRuleValidator}), не в DTO-аннотациях (зависит от значения другого поля).
 */
public record CustomFieldRuleCreateRequest(

        @NotBlank
        @Size(max = 255)
        String name,

        @Size(max = 500)
        String description,

        @NotNull
        MessageType messageType,

        @Size(max = 255)
        String templateName,

        @NotNull
        ExtractionSource extractionSource,

        @NotBlank
        String pattern,

        Boolean active
) {
}
