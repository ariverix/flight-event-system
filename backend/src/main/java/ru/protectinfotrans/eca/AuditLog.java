package ru.protectinfotrans.eca;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Журнал аудита — запись значимых операций в системе.
 *
 * См. диплом: раздел 1.3.4 (ключевые сущности)
 */
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

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
