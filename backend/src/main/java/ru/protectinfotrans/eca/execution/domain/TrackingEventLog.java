package ru.protectinfotrans.eca.execution.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * P1-8 (часть 1 — схема, V24): запись Event Log класса Tracking (SITA) — бизнес-журнал
 * для оператора, отдельный от {@link StepExecution} (техническая история ОДНОГО инстанса,
 * каскадно удаляется вместе с ним) и от {@code AuditLog} (действия пользователя, не события
 * движка). Переживает удаление {@code sequence}/{@code execution_instance} — без FK на них
 * намеренно (тот же принцип, что у {@link ExecutionInstance#getSequenceId()} — execution не
 * должен жёстко зависеть от sequence через схему).
 *
 * <p>Размещена в модуле {@code execution}, а не в cross-cutting корневом пакете (как
 * {@code AuditLog}): в отличие от audit_log, который пишут несколько модулей (sequence, user)
 * через собственный {@code AuditLogPort} в каждом, Tracking Event Log пишется ТОЛЬКО из одной
 * точки — пути выполнения инстанса последовательности (старт/стоп/завершение шага), который
 * целиком находится в модуле execution. Модуль sequence не пишет сюда напрямую — он только
 * хранит флаг {@code Sequence#isLoggingEnabled()}, который читает observability-agent перед
 * записью (через публичный API/порт модуля sequence, не через прямой доступ к таблице).
 *
 * <p>Логика ЗАПИСИ (вызовы save через порт) — часть 2, observability-agent. Здесь только
 * entity-маппинг под уже созданную миграцией V24 таблицу {@code tracking_event_log}.
 */
@Entity
@Table(name = "tracking_event_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackingEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Без FK — см. javadoc класса. */
    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    /** NULL для SEQUENCE_STARTED, зафиксированного до создания execution_instance. */
    @Column(name = "instance_id")
    private Long instanceId;

    @Column(name = "aircraft_id", nullable = false, length = 50)
    private String aircraftId;

    @Column(name = "flight_number", length = 50)
    private String flightNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private TrackingEventType eventType;

    /** Только для {@link TrackingEventType#STEP_COMPLETED}. */
    @Column(name = "step_index")
    private Integer stepIndex;

    /** SUCCESS/FAILURE — только для {@link TrackingEventType#STEP_COMPLETED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "step_result", length = 20)
    private StepResult stepResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "jsonb")
    private String detailsJson;

    /** Сквозной идентификатор запроса/сообщения — тот же формат, что audit_log.correlation_id (V20). */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
