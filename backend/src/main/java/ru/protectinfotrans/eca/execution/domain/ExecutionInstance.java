package ru.protectinfotrans.eca.execution.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// sequenceId — без FK намеренно: модуль execution не должен зависеть от sequence напрямую
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

    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

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

    /**
     * Метка последнего изменения стейта инстанса (любой save()), независимо от
     * конкретного жизненного события (start/wait/complete). Нужна для диагностики
     * "зависших" инстансов и должна обновляться в части 2 (sequence-engine-dev)
     * при каждом save-on-every-transition.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Заложено под будущую оптимистическую блокировку (P1-6). НЕ аннотировано
     * @Version намеренно — включение оптимистической блокировки (JPA-проверка
     * версии при update, ObjectOptimisticLockingFailureException и т.п.) — это
     * отдельная задача P1-6, не часть P1-3. Сейчас это обычная nullable-колонка,
     * не участвующая в логике конкурентного доступа.
     */
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (version == null) {
            version = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
