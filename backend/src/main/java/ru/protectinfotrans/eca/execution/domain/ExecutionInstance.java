package ru.protectinfotrans.eca.execution.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Экземпляр выполнения последовательности, привязанный к конкретному ВС.
 * sequenceId хранится как Long БЕЗ FK — межмодульное разделение (execution не ссылается на sequence напрямую).
 *
 * См. диплом: раздел 1.3.4 (ключевые сущности — ExecutionInstance)
 */
@Entity
@Table(name = "execution_instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** БЕЗ FK — межмодульная граница */
    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    /** Привязка к воздушному судну */
    @Column(name = "aircraft_id", nullable = false)
    private String aircraftId;

    @Column(name = "flight_number")
    private String flightNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status;

    @Column(name = "current_step_index")
    private Integer currentStepIndex;

    /** JSONB — контекст выполнения (активные условия, переменные) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context", columnDefinition = "jsonb")
    private String contextJson;

    /** Когда начали ожидание — для fromThisPointOnly в WAIT-шагах */
    @Column(name = "wait_started_at")
    private LocalDateTime waitStartedAt;

    /** Когда таймаут истечёт */
    @Column(name = "wait_timeout_at")
    private LocalDateTime waitTimeoutAt;

    @Builder.Default
    @OneToMany(mappedBy = "executionInstance", cascade = CascadeType.ALL)
    @OrderBy("executedAt ASC")
    private List<StepExecution> stepHistory = new ArrayList<>();

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
    }
}
