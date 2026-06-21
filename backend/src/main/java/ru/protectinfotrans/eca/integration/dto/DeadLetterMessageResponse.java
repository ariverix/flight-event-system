package ru.protectinfotrans.eca.integration.dto;

import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterSource;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;

import java.time.LocalDateTime;

/**
 * P2-6: DTO DLQ-записи для оператора (список/детали) — DTO != Entity (CLAUDE.md, "Стиль кода").
 */
public record DeadLetterMessageResponse(
        Long id,
        DeadLetterSource source,
        String format,
        String rawPayload,
        String requestContext,
        String reason,
        String stackTrace,
        DeadLetterStatus status,
        Integer attempts,
        Long reprocessedMessageId,
        String correlationId,
        LocalDateTime createdAt,
        LocalDateTime lastAttemptAt
) {
    public static DeadLetterMessageResponse fromEntity(DeadLetterMessage entity) {
        return new DeadLetterMessageResponse(
                entity.getId(),
                entity.getSource(),
                entity.getFormat(),
                entity.getRawPayload(),
                entity.getRequestContext(),
                entity.getReason(),
                entity.getStackTrace(),
                entity.getStatus(),
                entity.getAttempts(),
                entity.getReprocessedMessageId(),
                entity.getCorrelationId(),
                entity.getCreatedAt(),
                entity.getLastAttemptAt()
        );
    }
}
