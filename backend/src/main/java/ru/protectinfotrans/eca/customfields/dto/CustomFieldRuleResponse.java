package ru.protectinfotrans.eca.customfields.dto;

import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;

import java.time.LocalDateTime;

public record CustomFieldRuleResponse(
        Long id,
        String name,
        String description,
        MessageType messageType,
        String templateName,
        ExtractionSource extractionSource,
        String pattern,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static CustomFieldRuleResponse from(CustomFieldRule rule) {
        return new CustomFieldRuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getDescription(),
                rule.getMessageType(),
                rule.getTemplateName(),
                rule.getExtractionSource(),
                rule.getPattern(),
                rule.isActive(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
