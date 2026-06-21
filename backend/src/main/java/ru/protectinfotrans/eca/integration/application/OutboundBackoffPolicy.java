package ru.protectinfotrans.eca.integration.application;

import java.time.Duration;

/**
 * P2-6: экспоненциальный backoff для повторных попыток доставки исходящего сообщения —
 * чистая арифметика, без зависимостей от Spring/БД (юнит-тестируется напрямую).
 *
 * <p><b>Формула:</b> {@code delay = min(baseDelay * 2^attempts, maxDelay)} — {@code attempts} —
 * число ПРЕДЫДУЩИХ неудачных попыток (0 для первого повтора после первого сбоя, 1 после второго
 * и т.д.), {@code baseDelay}/{@code maxDelay} — конфигурация политики. Следующая попытка не
 * раньше {@code now + delay} (durable {@code next_attempt_at} на {@code OutboundMessage}, P2-6) —
 * поллер {@code OutboundMessageDeliveryScheduler} не долбит канал каждый тик подряд на каждый
 * сбойный кандидат.
 *
 * <p>Линейный backoff был рассмотрен и отклонён: экспоненциальный быстрее даёт каналу время на
 * восстановление при затяжном сбое (паритет с типичной промышленной практикой ретраев на внешние
 * системы — то же соображение, что обосновывает выбор экспоненциального roll-off в большинстве
 * production message-брокеров), при том что для МАЛОГО числа попыток (видим {@code MAX_ATTEMPTS=5}
 * в {@code OutboundMessageDeliveryScheduler}) разница в сложности реализации с линейным практически
 * нулевая — выбираем более устойчивый вариант без доп. цены.
 */
public final class OutboundBackoffPolicy {

    /** Первая задержка повтора (после 1-го сбоя, attempts=0): 5 секунд. */
    public static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(5);

    /** Верхний потолок задержки — не уходим в часы даже после многих сбоев подряд. */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(5);

    private final Duration baseDelay;
    private final Duration maxDelay;

    public OutboundBackoffPolicy() {
        this(DEFAULT_BASE_DELAY, DEFAULT_MAX_DELAY);
    }

    public OutboundBackoffPolicy(Duration baseDelay, Duration maxDelay) {
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay должен быть положительным");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay не может быть меньше baseDelay");
        }
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
    }

    /**
     * Задержка перед следующей попыткой.
     *
     * @param attemptsSoFar число попыток, УЖЕ потерпевших неудачу (0, 1, 2, ...)
     * @return задержка, ограниченная {@code maxDelay}
     */
    public Duration delayFor(int attemptsSoFar) {
        if (attemptsSoFar < 0) {
            throw new IllegalArgumentException("attemptsSoFar не может быть отрицательным");
        }
        // защита от переполнения при большом attemptsSoFar — выше ~40 итераций 2^n уже
        // гарантированно превышает maxDelay в наносекундах при любой разумной конфигурации
        if (attemptsSoFar > 40) {
            return maxDelay;
        }
        long multiplier = 1L << attemptsSoFar; // 2^attemptsSoFar
        Duration candidate = baseDelay.multipliedBy(multiplier);
        return candidate.compareTo(maxDelay) > 0 ? maxDelay : candidate;
    }
}
