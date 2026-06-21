package ru.protectinfotrans.eca.integration.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P2-6: чистая логика переходов состояния circuit breaker (CLOSED/OPEN/HALF_OPEN) — без
 * Spring/БД, см. javadoc {@link CircuitBreakerPolicy}.
 */
@DisplayName("CircuitBreakerPolicy")
class CircuitBreakerPolicyTest {

    private final CircuitBreakerPolicy defaultPolicy = new CircuitBreakerPolicy();

    // ============================================================
    // decideBeforeAttempt
    // ============================================================

    @Test
    @DisplayName("CLOSED -> ALLOW")
    void closedAllowsAttempt() {
        CircuitBreakerPolicy.Snapshot closed = CircuitBreakerPolicy.Snapshot.closedFresh();
        assertThat(defaultPolicy.decideBeforeAttempt(closed, LocalDateTime.now()))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW);
    }

    @Test
    @DisplayName("HALF_OPEN -> ALLOW_PROBE")
    void halfOpenAllowsProbe() {
        CircuitBreakerPolicy.Snapshot halfOpen =
                new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.HALF_OPEN, 5, LocalDateTime.now());
        assertThat(defaultPolicy.decideBeforeAttempt(halfOpen, LocalDateTime.now()))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);
    }

    @Test
    @DisplayName("OPEN, таймаут НЕ истёк -> BLOCK")
    void openBlocksBeforeTimeoutExpires() {
        LocalDateTime openedAt = LocalDateTime.now();
        CircuitBreakerPolicy.Snapshot open = new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.OPEN, 5, openedAt);

        CircuitBreakerPolicy.Decision decision = defaultPolicy.decideBeforeAttempt(
                open, openedAt.plus(CircuitBreakerPolicy.DEFAULT_OPEN_TIMEOUT).minusSeconds(1));

        assertThat(decision).isEqualTo(CircuitBreakerPolicy.Decision.BLOCK);
    }

    @Test
    @DisplayName("OPEN, таймаут истёк -> ALLOW_PROBE")
    void openAllowsProbeAfterTimeoutExpires() {
        LocalDateTime openedAt = LocalDateTime.now();
        CircuitBreakerPolicy.Snapshot open = new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.OPEN, 5, openedAt);

        CircuitBreakerPolicy.Decision decision = defaultPolicy.decideBeforeAttempt(
                open, openedAt.plus(CircuitBreakerPolicy.DEFAULT_OPEN_TIMEOUT).plusSeconds(1));

        assertThat(decision).isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);
    }

    @Test
    @DisplayName("OPEN, ровно момент восстановления (граница) -> ALLOW_PROBE (now не before recoverAt)")
    void openAtExactRecoveryMomentAllowsProbe() {
        LocalDateTime openedAt = LocalDateTime.now();
        CircuitBreakerPolicy.Snapshot open = new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.OPEN, 5, openedAt);
        LocalDateTime recoverAt = openedAt.plus(CircuitBreakerPolicy.DEFAULT_OPEN_TIMEOUT);

        assertThat(defaultPolicy.decideBeforeAttempt(open, recoverAt))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);
    }

    @Test
    @DisplayName("OPEN без openedAt (рассинхронизация данных) -> ALLOW_PROBE немедленно, не блокирует навечно")
    void openWithoutOpenedAtDoesNotBlockForever() {
        CircuitBreakerPolicy.Snapshot openNoTimestamp = new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.OPEN, 5, null);

        assertThat(defaultPolicy.decideBeforeAttempt(openNoTimestamp, LocalDateTime.now()))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);
    }

    // ============================================================
    // onSuccess
    // ============================================================

    @Test
    @DisplayName("onSuccess из любого состояния -> CLOSED со сброшенным счётчиком сбоев")
    void successAlwaysResetsToClosedFresh() {
        CircuitBreakerPolicy.Snapshot beforeHalfOpen =
                new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.HALF_OPEN, 5, LocalDateTime.now());

        CircuitBreakerPolicy.Snapshot after = defaultPolicy.onSuccess(beforeHalfOpen);

        assertThat(after.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(after.consecutiveFailures()).isZero();
        assertThat(after.openedAt()).isNull();
    }

    // ============================================================
    // onFailure
    // ============================================================

    @Test
    @DisplayName("onFailure из CLOSED, ниже порога -> инкремент счётчика, остаётся CLOSED")
    void failureBelowThresholdStaysClosed() {
        CircuitBreakerPolicy.Snapshot before = new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.CLOSED, 2, null);

        CircuitBreakerPolicy.Snapshot after = defaultPolicy.onFailure(before, LocalDateTime.now());

        assertThat(after.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(after.consecutiveFailures()).isEqualTo(3);
        assertThat(after.openedAt()).isNull();
    }

    @Test
    @DisplayName("onFailure из CLOSED, достигнут порог -> OPEN с новым openedAt")
    void failureReachingThresholdOpensBreaker() {
        CircuitBreakerPolicy.Snapshot before = new CircuitBreakerPolicy.Snapshot(
                CircuitBreakerState.CLOSED, CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD - 1, null);
        LocalDateTime now = LocalDateTime.now();

        CircuitBreakerPolicy.Snapshot after = defaultPolicy.onFailure(before, now);

        assertThat(after.state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(after.consecutiveFailures()).isEqualTo(CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD);
        assertThat(after.openedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("onFailure из HALF_OPEN (пробная попытка сорвалась) -> снова OPEN с НОВЫМ openedAt, счётчик не растёт дальше")
    void failureDuringHalfOpenProbeReopensWithFreshTimeout() {
        LocalDateTime oldOpenedAt = LocalDateTime.now().minusMinutes(5);
        CircuitBreakerPolicy.Snapshot halfOpen =
                new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.HALF_OPEN, 5, oldOpenedAt);
        LocalDateTime now = LocalDateTime.now();

        CircuitBreakerPolicy.Snapshot after = defaultPolicy.onFailure(halfOpen, now);

        assertThat(after.state()).isEqualTo(CircuitBreakerState.OPEN);
        assertThat(after.consecutiveFailures()).isEqualTo(5); // не инкрементирован повторно
        assertThat(after.openedAt()).isEqualTo(now);
        assertThat(after.openedAt()).isNotEqualTo(oldOpenedAt);
    }

    @Test
    @DisplayName("полный цикл: серия сбоев открывает breaker, успешная HALF_OPEN проба закрывает обратно")
    void fullLifecycleClosedToOpenToHalfOpenToClosed() {
        CircuitBreakerPolicy.Snapshot state = CircuitBreakerPolicy.Snapshot.closedFresh();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD; i++) {
            assertThat(defaultPolicy.decideBeforeAttempt(state, now)).isEqualTo(CircuitBreakerPolicy.Decision.ALLOW);
            state = defaultPolicy.onFailure(state, now);
        }
        assertThat(state.state()).isEqualTo(CircuitBreakerState.OPEN);

        // до таймаута — BLOCK
        assertThat(defaultPolicy.decideBeforeAttempt(state, now.plusSeconds(1)))
                .isEqualTo(CircuitBreakerPolicy.Decision.BLOCK);

        // после таймаута — ALLOW_PROBE, пробная попытка успешна -> CLOSED
        LocalDateTime afterTimeout = now.plus(CircuitBreakerPolicy.DEFAULT_OPEN_TIMEOUT).plusSeconds(1);
        assertThat(defaultPolicy.decideBeforeAttempt(state, afterTimeout))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);

        CircuitBreakerPolicy.Snapshot afterProbeSuccess = defaultPolicy.onSuccess(state);
        assertThat(afterProbeSuccess.state()).isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(afterProbeSuccess.consecutiveFailures()).isZero();
    }

    // ============================================================
    // конструктор — валидация
    // ============================================================

    @Test
    @DisplayName("failureThreshold <= 0 -> IllegalArgumentException")
    void nonPositiveFailureThresholdRejected() {
        assertThatThrownBy(() -> new CircuitBreakerPolicy(0, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerPolicy(-1, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("кастомный порог/таймаут применяются вместо дефолтных")
    void customThresholdAndTimeoutApplied() {
        CircuitBreakerPolicy custom = new CircuitBreakerPolicy(2, Duration.ofSeconds(5));
        LocalDateTime now = LocalDateTime.now();

        CircuitBreakerPolicy.Snapshot afterFirstFailure =
                custom.onFailure(new CircuitBreakerPolicy.Snapshot(CircuitBreakerState.CLOSED, 0, null), now);
        assertThat(afterFirstFailure.state()).isEqualTo(CircuitBreakerState.CLOSED);

        CircuitBreakerPolicy.Snapshot afterSecondFailure = custom.onFailure(afterFirstFailure, now);
        assertThat(afterSecondFailure.state()).isEqualTo(CircuitBreakerState.OPEN);

        assertThat(custom.decideBeforeAttempt(afterSecondFailure, now.plusSeconds(4)))
                .isEqualTo(CircuitBreakerPolicy.Decision.BLOCK);
        assertThat(custom.decideBeforeAttempt(afterSecondFailure, now.plusSeconds(6)))
                .isEqualTo(CircuitBreakerPolicy.Decision.ALLOW_PROBE);
    }
}
