package ru.protectinfotrans.eca.execution.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import java.time.LocalDateTime;

/**
 * Запись истории выполнения шага — фиксирует результат и принятое решение.
 *
 * См. диплом: раздел 1.3.4 (ключевые сущности)
 */
@Entity
@Table(name = "step_executions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_instance_id", nullable = false)
    private ExecutionInstance executionInstance;

    private Integer stepIndex;

    @Enumerated(EnumType.STRING)
    private StepType stepType;

    @Enumerated(EnumType.STRING)
    private StepResult result;

    /** Какое решение Result Decision Maker было принято */
    @Enumerated(EnumType.STRING)
    private TransitionAction transitionAction;

    /** Номер шага для GOTO (null для остальных) */
    private Integer transitionTarget;

    /** JSONB — детали выполнения */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detailsJson;

    private LocalDateTime executedAt;

    @PrePersist
    protected void onCreate() {
        executedAt = LocalDateTime.now();
    }
}
