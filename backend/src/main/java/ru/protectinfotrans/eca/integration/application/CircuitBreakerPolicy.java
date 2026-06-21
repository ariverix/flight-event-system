package ru.protectinfotrans.eca.integration.application;

import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * P2-6: собственная лёгкая реализация circuit breaker (CLOSED/OPEN/HALF_OPEN) — чистая логика
 * переходов состояния, без зависимости от Spring/БД (юнит-тестируется напрямую через
 * {@link Snapshot}). Персистентность снимка — {@code ChannelCircuitBreaker}/
 * {@code ChannelCircuitBreakerJpaRepository}; вызывающая сторона
 * ({@code OutboundMessageDeliveryScheduler}) читает текущий снимок, спрашивает
 * {@link #decideBeforeAttempt}, и по факту попытки сообщает {@link #onSuccess}/{@link #onFailure}.
 *
 * <p><b>Почему собственная реализация, а не resilience4j</b> (см. также javadoc
 * {@code ChannelCircuitBreaker}): резистенс4j спроектирован вокруг in-memory
 * {@code CircuitBreakerRegistry} в рамках ОДНОГО процесса — состояние не переживает рестарт без
 * дополнительной обвязки персистентности, которую всё равно придётся писать самостоятельно (то
 * же, что эта реализация делает прямо), и тащит лишний слой конфигурации/абстракций
 * (Resilience4j EventPublisher, decorators, registries) для задачи с ОДНИМ типом защищаемого
 * ресурса и тремя простыми состояниями. Дополнительная внешняя зависимость без выигрыша в
 * функциональности — против духа "минимум абстракций на будущее" и импортозамещения (CLAUDE.md).
 *
 * <p><b>Пороги (см. {@link #DEFAULT_FAILURE_THRESHOLD}/{@link #DEFAULT_OPEN_TIMEOUT}):</b>
 * {@code failureThreshold} подряд идущих сбоев в {@code CLOSED} -> {@code OPEN}; через
 * {@code openTimeout} после перехода в {@code OPEN} -> разрешена ОДНА пробная попытка
 * ({@code HALF_OPEN}); успех пробной попытки -> {@code CLOSED} (счётчик сбоев сброшен в 0); сбой
 * пробной попытки -> снова {@code OPEN} с новым {@code openedAt} (новый отсчёт таймаута).
 */
public final class CircuitBreakerPolicy {

    /** Порог подряд идущих сбоев, открывающий breaker. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 5;

    /** Таймаут восстановления — сколько ждать в OPEN до пробной HALF_OPEN попытки. */
    public static final Duration DEFAULT_OPEN_TIMEOUT = Duration.ofSeconds(30);

    private final int failureThreshold;
    private final Duration openTimeout;

    public CircuitBreakerPolicy() {
        this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_TIMEOUT);
    }

    public CircuitBreakerPolicy(int failureThreshold, Duration openTimeout) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold должен быть положительным");
        }
        this.failureThreshold = failureThreshold;
        this.openTimeout = openTimeout;
    }

    /** Неизменяемый снимок текущего состояния breaker канала на момент решения. */
    public record Snapshot(CircuitBreakerState state, int consecutiveFailures, LocalDateTime openedAt) {
        public static Snapshot closedFresh() {
            return new Snapshot(CircuitBreakerState.CLOSED, 0, null);
        }
    }

    /** Решение, которое breaker принимает ПЕРЕД попыткой доставки. */
    public enum Decision {
        /** Доставку можно пробовать как обычно. */
        ALLOW,
        /** Breaker открыт и таймаут восстановления не истёк — fail-fast, без обращения к каналу. */
        BLOCK,
        /** Таймаут истёк — разрешена РОВНО ОДНА пробная попытка (HALF_OPEN). */
        ALLOW_PROBE
    }

    /**
     * Решение, можно ли пытаться доставить сообщение через канал ПРЯМО СЕЙЧАС.
     *
     * @param snapshot текущее состояние breaker (читается из БД ДО попытки)
     * @param now      текущее время (передаётся явно — юнит-тесты не зависят от системных часов)
     */
    public Decision decideBeforeAttempt(Snapshot snapshot, LocalDateTime now) {
        switch (snapshot.state()) {
            case CLOSED -> {
                return Decision.ALLOW;
            }
            case HALF_OPEN -> {
                // HALF_OPEN уже означает "пробная попытка разрешена и в процессе" — повторный
                // запрос решения в этом состоянии (до того, как onSuccess/onFailure отметили
                // результат) тоже получает ALLOW_PROBE, вызывающая сторона (claim в БД) сама
                // гарантирует, что фактическая отправка не продублируется.
                return Decision.ALLOW_PROBE;
            }
            case OPEN -> {
                if (snapshot.openedAt() == null) {
                    // защита от рассинхронизации данных — OPEN без openedAt не должен бесконечно
                    // блокировать канал, считаем таймаут истёкшим немедленно.
                    return Decision.ALLOW_PROBE;
                }
                LocalDateTime recoverAt = snapshot.openedAt().plus(openTimeout);
                return now.isBefore(recoverAt) ? Decision.BLOCK : Decision.ALLOW_PROBE;
            }
            default -> throw new IllegalStateException("Неизвестное состояние breaker: " + snapshot.state());
        }
    }

    /** Снимок ПОСЛЕ успешной доставки — breaker закрывается, счётчик сбоев сброшен. */
    public Snapshot onSuccess(Snapshot before) {
        return Snapshot.closedFresh();
    }

    /**
     * Снимок ПОСЛЕ неудачной попытки доставки.
     *
     * <p>Из {@code HALF_OPEN} сбой пробной попытки -> снова {@code OPEN} с НОВЫМ {@code openedAt}
     * (новый отсчёт таймаута, счётчик сбоев не наращиваем дальше — он уже на уровне порога).
     * Из {@code CLOSED} — инкремент счётчика; при достижении порога -> {@code OPEN}.
     */
    public Snapshot onFailure(Snapshot before, LocalDateTime now) {
        if (before.state() == CircuitBreakerState.HALF_OPEN) {
            return new Snapshot(CircuitBreakerState.OPEN, before.consecutiveFailures(), now);
        }

        int failures = before.consecutiveFailures() + 1;
        if (failures >= failureThreshold) {
            return new Snapshot(CircuitBreakerState.OPEN, failures, now);
        }
        return new Snapshot(CircuitBreakerState.CLOSED, failures, before.openedAt());
    }
}
