package ru.protectinfotrans.eca.integration.domain;

/**
 * Статусная модель durable-доставки исходящего сообщения (P2-3).
 *
 * <p>{@code PENDING -> SENDING -> SENT}: {@code SENDING} — промежуточный статус атомарного
 * claim (по аналогии с claim-ом WAIT-таймаутов, P1-5) — single-fire доставки под конкуренцией
 * нескольких поллеров/реплик. {@code PENDING -> SENDING -> FAILED}: базовый повтор —
 * полноценные backoff/circuit breaker/DLQ — отдельная задача P2-6, здесь только статус
 * и счётчик попыток.
 */
public enum OutboundMessageStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED
}
