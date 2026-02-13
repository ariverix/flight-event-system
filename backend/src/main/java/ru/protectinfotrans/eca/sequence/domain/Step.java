package ru.protectinfotrans.eca.sequence.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Шаг последовательности — единица выполнения (ACTION, EVALUATE или WAIT).
 * Каждый шаг содержит Result Decision Maker для управления потоком.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — 3 типа шагов), раздел 1.3.4
 */
@Entity
@Table(name = "steps")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Step {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sequence_id", nullable = false)
    private Sequence sequence;

    /** Порядковый номер шага (1, 2, 3...) */
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Column(length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false)
    private StepType stepType;

    /** JSONB — конфигурация шага (actionType, criterionType, параметры) */
    @Column(name = "config", columnDefinition = "jsonb")
    private String configJson;

    /** Таймаут для WAIT-шагов в секундах (null для ACTION/EVALUATE) */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    // --- Result Decision Maker: SUCCESS ---

    @Enumerated(EnumType.STRING)
    @Column(name = "on_success_action", nullable = false)
    private TransitionAction onSuccessAction;

    /** Номер шага для GOTO (null если не GOTO) */
    @Column(name = "on_success_goto_step")
    private Integer onSuccessGotoStep;

    /** Уведомить оператора при успехе. См. диплом: раздел 1.2.2 (Notify) */
    @Builder.Default
    @Column(name = "on_success_notify")
    private Boolean onSuccessNotify = false;

    // --- Result Decision Maker: FAILURE ---

    @Enumerated(EnumType.STRING)
    @Column(name = "on_failure_action", nullable = false)
    private TransitionAction onFailureAction;

    @Column(name = "on_failure_goto_step")
    private Integer onFailureGotoStep;

    @Builder.Default
    @Column(name = "on_failure_notify")
    private Boolean onFailureNotify = false;
}
