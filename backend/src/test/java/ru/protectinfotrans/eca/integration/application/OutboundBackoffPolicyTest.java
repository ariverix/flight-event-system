package ru.protectinfotrans.eca.integration.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-6: чистая арифметика экспоненциального backoff — без Spring/БД, см. javadoc
 * {@link OutboundBackoffPolicy} для формулы {@code delay = min(baseDelay * 2^attempts, maxDelay)}.
 */
@DisplayName("OutboundBackoffPolicy")
class OutboundBackoffPolicyTest {

    private final OutboundBackoffPolicy defaultPolicy = new OutboundBackoffPolicy();

    @Test
    @DisplayName("attemptsSoFar=0 -> delay = baseDelay (первый повтор после первого сбоя)")
    void firstRetryUsesBaseDelay() {
        assertThat(defaultPolicy.delayFor(0)).isEqualTo(OutboundBackoffPolicy.DEFAULT_BASE_DELAY);
    }

    @Test
    @DisplayName("attemptsSoFar=1 -> delay = baseDelay * 2")
    void secondRetryDoublesDelay() {
        assertThat(defaultPolicy.delayFor(1)).isEqualTo(OutboundBackoffPolicy.DEFAULT_BASE_DELAY.multipliedBy(2));
    }

    @Test
    @DisplayName("attemptsSoFar=2 -> delay = baseDelay * 4")
    void thirdRetryQuadruplesDelay() {
        assertThat(defaultPolicy.delayFor(2)).isEqualTo(OutboundBackoffPolicy.DEFAULT_BASE_DELAY.multipliedBy(4));
    }

    @Test
    @DisplayName("большое число попыток -> delay ограничен maxDelay (потолок, не растёт бесконечно)")
    void delayClampedAtMaxDelay() {
        assertThat(defaultPolicy.delayFor(20)).isEqualTo(OutboundBackoffPolicy.DEFAULT_MAX_DELAY);
    }

    @Test
    @DisplayName("экстремально большое attemptsSoFar (>40) -> возвращает maxDelay без переполнения/исключения")
    void extremeAttemptsCountDoesNotOverflow() {
        assertThat(defaultPolicy.delayFor(1000)).isEqualTo(OutboundBackoffPolicy.DEFAULT_MAX_DELAY);
    }

    @Test
    @DisplayName("отрицательное attemptsSoFar -> IllegalArgumentException")
    void negativeAttemptsRejected() {
        assertThatThrownBy(() -> defaultPolicy.delayFor(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("кастомная конфигурация — baseDelay/maxDelay применяются вместо дефолтных")
    void customConfigurationOverridesDefaults() {
        OutboundBackoffPolicy custom = new OutboundBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10));

        assertThat(custom.delayFor(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(custom.delayFor(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(custom.delayFor(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(custom.delayFor(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(custom.delayFor(4)).isEqualTo(Duration.ofSeconds(10)); // 16s -> clamp к maxDelay=10s
    }

    @Test
    @DisplayName("baseDelay <= 0 -> IllegalArgumentException (конструктор)")
    void nonPositiveBaseDelayRejected() {
        assertThatThrownBy(() -> new OutboundBackoffPolicy(Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboundBackoffPolicy(Duration.ofSeconds(-1), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("maxDelay < baseDelay -> IllegalArgumentException (конструктор)")
    void maxDelayLessThanBaseDelayRejected() {
        assertThatThrownBy(() -> new OutboundBackoffPolicy(Duration.ofMinutes(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
