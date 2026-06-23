package ru.protectinfotrans.eca.eventhandling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Запись о доставленном уведомлении (P3-4) — durable дедуп-реестр. Ключ идемпотентности
 * {@code (executionId, stepIndex, result, handlerId)} закрыт частичным UNIQUE (V34): повторная
 * доставка {@code StepNotificationEvent} (at-least-once republish/retry, ADR-0002) даёт конфликт →
 * no-op, реальный канал дёргается ровно один раз на (шаг-исход, обработчик).
 */
@Entity
@Table(name = "notification_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    /** SUCCESS | FAILURE — часть дедуп-ключа (success и failure одного шага — разные уведомления). */
    @Column(nullable = false, length = 20)
    private String result;

    @Column(name = "handler_id", nullable = false)
    private Long handlerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(nullable = false, length = 500)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "delivered_at", nullable = false)
    private LocalDateTime deliveredAt;

    @PrePersist
    protected void onCreate() {
        if (deliveredAt == null) {
            deliveredAt = LocalDateTime.now();
        }
    }
}
