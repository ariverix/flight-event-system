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
     * P1-6: активная оптимистическая блокировка JPA. Колонка добавлена и бэкафиллена
     * нулём миграцией V22 (P1-3) как раз ради этого включения — см. комментарий там.
     * Hibernate сам инкрементирует version при каждом UPDATE через {@code save()} и
     * бросает {@link org.springframework.orm.ObjectOptimisticLockingFailureException}
     * (Spring оборачивает {@code OptimisticLockException}), если строка в БД успела
     * измениться между чтением и записью этой же транзакции — это и закрывает гонку
     * "два потока конкурентно читают/пишут ОДИН И ТОТ ЖЕ инстанс" (см.
     * {@code ExecutionService} — обработка одного NormalizedEvent на инстанс теперь
     * в собственной {@code REQUIRES_NEW}-транзакции с retry на конфликте версии).
     *
     * <p><b>Совместимость с claim-логикой таймаутов (P1-5):</b>
     * {@code ExecutionJpaRepository#claimExpiredTimeout} — это {@code @Modifying}
     * bulk JPQL UPDATE с {@code clearAutomatically = true}. Bulk UPDATE выполняется
     * напрямую в БД через JPQL-предикат {@code wait_timeout_at = :expectedTimeout} и
     * НЕ проходит через Hibernate dirty-checking механизм — column version в нём не
     * участвует и не инкрементируется (это нормально и ожидаемо для bulk-операций:
     * у самого claim'а уже есть собственный атомарный предикат на уровне СТРОКИ,
     * описанный в его javadoc, который даёт ту же гарантию single-fire без участия
     * @Version). {@code clearAutomatically=true} очищает persistence context сразу
     * после claim — следующий {@code findById} в {@code claimAndAdvanceTimeout}
     * перечитывает строку заново вместе с её АКТУАЛЬНЫМ version (тем, что реально в
     * БД), так что дальнейший {@code advanceExecution → save()} в той же транзакции
     * не словит мнимый конфликт версии из-за устаревшего in-memory значения.
     *
     * <p>Колонка nullable на уровне БД (V22), но {@code @PrePersist} здесь и
     * {@code DEFAULT 0} в БД гарантируют, что фактическое значение всегда не NULL
     * для каждой когда-либо сохранённой через JPA строки — отдельная миграция
     * "version NOT NULL" не требуется для корректной работы {@code @Version}.
     */
    @Version
    @Column(name = "version", nullable = false)
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
