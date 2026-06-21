package ru.protectinfotrans.eca.integration.port.out;

import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;

import java.time.LocalDateTime;

/**
 * P2-6: выходной порт хранения durable-состояния circuit breaker на внешние каналы доставки.
 * Внутренний порт модуля {@code integration} — НЕ выставляется как named-interface наружу (тот
 * же принцип, что {@link OutboundMessageRepositoryPort}).
 */
public interface CircuitBreakerRepositoryPort {

    /** Текущее состояние канала — создаёт CLOSED-запись по умолчанию, если её ещё нет (lazy init). */
    ChannelCircuitBreaker getOrCreate(OutboundMessageType channel);

    /** Успех -> CLOSED, счётчик сбоев сброшен. */
    void recordSuccess(OutboundMessageType channel);

    /**
     * Сбой -> либо инкремент счётчика (CLOSED), либо открытие/повторное открытие (см.
     * {@code CircuitBreakerPolicy#onFailure}). {@code openedAt} передаётся вызывающей стороной
     * (вычислен через {@code CircuitBreakerPolicy}), чтобы вся арифметика порогов оставалась в
     * чистом, юнит-тестируемом классе политики, а не в репозитории.
     */
    void recordFailure(OutboundMessageType channel, boolean shouldOpen, int newConsecutiveFailures,
                        LocalDateTime openedAt);

    /**
     * Атомарный claim единственной HALF_OPEN пробной попытки — условный UPDATE
     * {@code OPEN -> HALF_OPEN} (тот же паттерн single-fire claim, что
     * {@code OutboundMessageRepositoryPort#claimPending}, P2-3/P1-5).
     *
     * @return {@code true}, если claim удался — ровно один вызывающий поток/реплика получает
     *         {@code true} для данного канала, остальные конкурентные вызовы получают
     *         {@code false} и не должны выполнять пробную отправку.
     */
    boolean claimHalfOpenProbe(OutboundMessageType channel);
}
