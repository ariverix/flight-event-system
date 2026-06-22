package ru.protectinfotrans.eca.customfields.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;

/**
 * Запрос на обновление правила. Имя НЕ переименовывается (тот же принцип, что
 * {@code TemplateUpdateRequest} — имя является стабильным ключом подстановки
 * {@code {{customField.NAME}}} и ключом per-flight хранения; переименование задним числом тихо
 * "отвязало" бы уже извлечённые значения и ссылки в шаблонах/критериях).
 */
public record CustomFieldRuleUpdateRequest(

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

        @NotNull
        Boolean active
) {
}
