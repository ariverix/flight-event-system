package ru.protectinfotrans.eca;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** Тип операции: CREATE_SEQUENCE, ACTIVATE_SEQUENCE, USER_LOGIN и т.д. */
    private String action;

    /** Тип сущности: SEQUENCE, EXECUTION, USER */
    private String entityType;

    private Long entityId;

    /** JSONB — дополнительные детали операции */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detailsJson;

    private LocalDateTime createdAt;

    /**
     * Сквозной идентификатор запроса/сообщения (см. {@link CorrelationContext#CORRELATION_ID}),
     * связывающий запись аудита со структурными JSON-логами. Nullable: исторические записи
     * и системные действия без HTTP-контекста его не имеют.
     */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
