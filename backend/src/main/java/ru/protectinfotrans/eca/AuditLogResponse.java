package ru.protectinfotrans.eca;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * DTO ответа для журнала аудита.
 *
 * См. диплом: раздел 1.3.4 (AuditLog — журнал аудита)
 */
@Value
@Builder
public class AuditLogResponse {

    Long id;
    Long userId;
    String action;
    String entityType;
    Long entityId;
    String detailsJson;
    LocalDateTime createdAt;

    /** Конвертация из сущности в DTO */
    public static AuditLogResponse fromEntity(AuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .action(log.getAction())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .detailsJson(log.getDetailsJson())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
