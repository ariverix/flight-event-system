package ru.protectinfotrans.eca.integration.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P2-3: durable исходящий шлюз — персистентная запись об отправке uplink/ground сообщения
 * по команде ECA-движка (ACTION SEND_UPLINK/SEND_GROUND).
 *
 * <p><b>Атомарность с переходом:</b> {@code ActionStepRule} вызывает
 * {@code execution.port.out.MessageOutputPort} СИНХРОННО в рамках транзакции перехода
 * (ADR-0002, Decision п.2 — ACTION-шаг обязан знать SUCCESS/FAILURE немедленно). Адаптер
 * {@code OutboundMessageGatewayAdapter} в ЭТОЙ ЖЕ транзакции только ПЕРСИСТИТ запись со
 * статусом {@link OutboundMessageStatus#PENDING} — синхронный вызов возвращает успех
 * постановки в durable-очередь, а не успех фактической внешней доставки. Сама доставка во
 * внешний канал — отдельный durable-поллер ({@code OutboundMessageDeliveryScheduler}),
 * переживающий рестарт процесса (запись в БД не теряется при краше между постановкой и
 * фактической отправкой — то же свойство, что и у durable WAIT-таймаутов P1-5).
 *
 * <p>Без FK на {@code execution_instances}/{@code sequences} — тот же принцип, что у
 * {@link ru.protectinfotrans.eca.execution.domain.ExecutionInstance#getSequenceId()} и
 * {@link ru.protectinfotrans.eca.execution.domain.TrackingEventLog}: запись переживает
 * удаление инстанса/последовательности, модуль integration не зависит от схемы execution.
 */
@Entity
@Table(name = "outbound_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private OutboundMessageType messageType;

    @Column(name = "aircraft_id", length = 50)
    private String aircraftId;

    @Column(name = "flight_number", length = 50)
    private String flightNumber;

    /** Только для GROUND — список получателей. NULL для UPLINK. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recipients", columnDefinition = "jsonb")
    private List<String> recipients;

    @Column(name = "template_name", nullable = false, length = 255)
    private String templateName;

    /** Параметры шаблона (то же, что приходит в {@code MessageOutputPort#sendUplink/sendGround}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    private String paramsJson;

    /** Только для UPLINK — computer-generated | external-user (паритет SITA). NULL для GROUND. */
    @Enumerated(EnumType.STRING)
    @Column(name = "uplink_origin", length = 30)
    private UplinkOrigin uplinkOrigin;

    /**
     * Дедуп-ключ идемпотентности (вместе с {@link #stepOrderIndex}) — фикс регрессии
     * идемпотентности P1-4 x P2-3 (см. {@code MessageOutputPort.sendUplink/sendGround},
     * 6-арг. перегрузка, и {@code ExecutionService#resumeRunningInstanceAfterRestart}).
     * Без FK на {@code execution_instances} — тот же принцип "без FK", что у остальных полей
     * этой сущности (см. javadoc класса): запись о постановке в очередь переживает удаление
     * инстанса. {@code NULL}, если {@code sendUplink}/{@code sendGround} вызван ВНЕ контекста
     * ACTION-шага ECA-движка (например {@code IntegrationService} — ручной/программный вызов) —
     * для таких записей дедуп по этому ключу не применяется (как и для NULL
     * {@code triggering_message_id} в P1-7).
     */
    @Column(name = "execution_instance_id")
    private Long executionInstanceId;

    /** См. {@link #executionInstanceId} — вторая часть составного дедуп-ключа. */
    @Column(name = "step_order_index")
    private Integer stepOrderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboundMessageStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    /** Сквозной идентификатор для Event Log/корреляции по борту/рейсу (тот же формат, что audit_log V20). */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "last_error")
    private String lastError;

    /**
     * Активная оптимистическая блокировка не вводится здесь намеренно — single-fire доставки
     * обеспечивается атомарным условным UPDATE (claim, см. {@code OutboundMessageJpaRepository#claimPending})
     * по аналогии с {@code ExecutionJpaRepository#claimExpiredTimeout} (P1-5), а не через
     * {@code @Version}: доставка — одношаговый переход {@code PENDING -> SENDING}, не
     * многошаговый бизнес-процесс с конкурентными READ-MODIFY-WRITE из разных мест кода.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = OutboundMessageStatus.PENDING;
        }
        if (attempts == null) {
            attempts = 0;
        }
    }
}
