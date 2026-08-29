package ru.protectinfotrans.eca.integration.port.out;

import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P2-6: выходной порт хранения durable-состояния circuit breaker на внешние каналы доставки.
 * Внутренний порт модуля {@code integration} — НЕ выставляется как named-interface наружу (тот
 * же принцип, что {@link OutboundMessageRepositoryPort}).
 */
public interface CircuitBreakerRepositoryPort {

    /** Текущее состояние канала — создаёт CLOSED-запись по умолчанию, если её ещё нет (lazy init). */
    ChannelCircuitBreaker getOrCreate(OutboundMessageType channel);

    /**
     * Список текущих состояний всех зарегистрированных circuit breaker'ов.
     * Используется {@code IntegrationChannelsHealthIndicator} (P5-3) для health readiness-группы —
     * read-only, не создаёт новых записей (в отличие от {@link #getOrCreate}).
     */
    List<ChannelCircuitBreaker> findAll();

    /** Успех -> CLOSED, счётчик сбоев сброшен. */
    void recordSuccess(OutboundMessageType channel);

    /**
     * Issue #1: атомарный инкремент {@code consecutiveFailures} + переход состояния — ОДНИМ
     * SQL {@code UPDATE}, целиком читающим текущее состояние строки канала В МОМЕНТ выполнения
     * (см. {@code ChannelCircuitBreakerJpaRepository#recordFailure} для полного обоснования и
     * SQL). Раньше метод принимал уже вычисленное вызывающей стороной абсолютное
     * {@code newConsecutiveFailures} (снимок+1 над снимком, прочитанным ДО попытки доставки) —
     * под конкуренцией двух {@code deliverOne} того же канала (в т.ч. из разных реплик в HA,
     * P6-1) это была classic lost-update гонка: оба потока писали одно и то же число, часть
     * реальных сбоев не засчитывалась, и breaker мог не открыться при достижении порога.
     * Сигнатура намеренно НЕ принимает вычисленное значение счётчика/{@code shouldOpen} —
     * сама возможность передать устаревший снимок исключена конструктивно.
     *
     * @param failureThreshold порог подряд идущих сбоев, открывающий breaker (см.
     *                         {@code CircuitBreakerPolicy#DEFAULT_FAILURE_THRESHOLD})
     * @param now              момент сбоя — используется как новый {@code openedAt}, ТОЛЬКО
     *                         если этот вызов переводит канал в {@code OPEN} прямо сейчас
     */
    void recordFailure(OutboundMessageType channel, int failureThreshold, LocalDateTime now);

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
