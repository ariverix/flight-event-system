package ru.protectinfotrans.eca.templates.dto;

import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.templates.domain.Template;

import java.time.LocalDateTime;
import java.util.Set;

public record TemplateResponse(
        Long id,
        String name,
        String description,
        MessageType messageType,
        UplinkOrigin origin,
        String category,
        String body,
        Set<String> variableNames,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static TemplateResponse from(Template template, Set<String> variableNames) {
        return new TemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getMessageType(),
                template.getOrigin(),
                template.getCategory(),
                template.getBody(),
                variableNames,
                template.isActive(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
