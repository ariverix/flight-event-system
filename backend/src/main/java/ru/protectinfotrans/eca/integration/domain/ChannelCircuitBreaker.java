package ru.protectinfotrans.eca.integration.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * P2-6: durable состояние circuit breaker на внешний канал доставки исходящих сообщений —
 * одна строка на {@link OutboundMessageType} (канал UPLINK/GROUND).
 *
 * <p><b>Собственная лёгкая реализация (не resilience4j):</b> состояние держится в БД (не
 * in-memory) — тот же принцип durability, что у {@code OutboundMessage}/WAIT-таймаутов
 * (P1-5/P2-3): переживает рестарт процесса, не теряет накопленные сбои "впустую" после краша.
 * In-memory (resilience4j по умолчанию) было бы регрессией относительно остальной кодовой базы,
 * где ВСЁ, что влияет на корректность поведения системы, durable. Полноценный resilience4j
 * избыточен для текущего объёма (один тип ресурса — канал доставки, три состояния, простая
 * арифметика порогов) и тянет внешнюю зависимость без нужды (импортозамещение, CLAUDE.md)."
 *
 * <p>Без {@code @Version} — переходы состояния выполняются атомарными условными UPDATE
 * (см. {@code ChannelCircuitBreakerJpaRepository}), тот же паттерн single-fire claim, что у
 * {@code OutboundMessage}/{@code ExecutionInstance} (P1-5/P2-3): не многошаговый
 * READ-MODIFY-WRITE из разных мест кода, а одношаговые условные переходы.
 */
@Entity
@Table(name = "channel_circuit_breakers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelCircuitBreaker {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    private OutboundMessageType channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CircuitBreakerState state;

    /** Число подряд идущих сбоев в CLOSED-состоянии (сбрасывается на любой успех). */
    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures;

    /** Момент перехода в OPEN — от него отсчитывается таймаут восстановления до HALF_OPEN. */
    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onChange() {
        if (state == null) {
            state = CircuitBreakerState.CLOSED;
        }
        if (consecutiveFailures == null) {
            consecutiveFailures = 0;
        }
        updatedAt = LocalDateTime.now();
    }
}
