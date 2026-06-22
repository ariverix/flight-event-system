package ru.protectinfotrans.eca.templates.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

/**
 * Запрос на обновление шаблона. Имя НЕ переименовывается (см. {@code TemplateService#update}) —
 * имя является стабильным идентификатором, на который ссылаются ACTION-конфиги и критерии по
 * строке; переименование задним числом тихо "отвязало" бы существующие ссылки. Чтобы сменить
 * имя — нужно создать новый шаблон и деактивировать старый.
 */
public record TemplateUpdateRequest(

        @Size(max = 500)
        String description,

        @NotNull
        MessageType messageType,

        UplinkOrigin origin,

        @Size(max = 100)
        String category,

        @NotBlank
        String body,

        @NotNull
        Boolean active
) {
}
