package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.cluster.ApplicationReadiness;
import ru.protectinfotrans.eca.cluster.LeaderElection;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P2-3 (гигиена старта): unit-тесты гейтов {@link WaitTimeoutScheduler#pollWaitTimeouts()}.
 *
 * <p>Проверяется, что автоматический тик НЕ выполняет бизнес-работу
 * ({@link ExecutionService#checkWaitTimeouts()} не вызывается), пока приложение не готово
 * ({@link ApplicationReadiness#isReady()} == false) или реплика не лидер
 * ({@link LeaderElection#isLeader()} == false); и что при готовности+лидерстве работа выполняется.
 * Оба гейта — функциональные интерфейсы, поэтому подставляются лямбда-заглушками
 * (тот же приём, что и для {@code LeaderElection} в других тестах поллеров).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitTimeoutScheduler — гейты готовности/лидерства (P2-3/P6-1)")
class WaitTimeoutSchedulerTest {

    @Mock
    private ExecutionService executionService;

    private WaitTimeoutScheduler scheduler(ApplicationReadiness readiness, LeaderElection leader) {
        return new WaitTimeoutScheduler(executionService, leader, readiness, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("приложение не готово (isReady=false) → checkWaitTimeouts НЕ вызывается, даже если лидер")
    void doesNothingWhenNotReady() {
        WaitTimeoutScheduler scheduler = scheduler(() -> false, () -> true);

        scheduler.pollWaitTimeouts();

        verifyNoInteractions(executionService);
    }

    @Test
    @DisplayName("готово, но не лидер (isLeader=false) → checkWaitTimeouts НЕ вызывается")
    void doesNothingWhenNotLeader() {
        WaitTimeoutScheduler scheduler = scheduler(() -> true, () -> false);

        scheduler.pollWaitTimeouts();

        verify(executionService, never()).checkWaitTimeouts();
    }

    @Test
    @DisplayName("готово и лидер → checkWaitTimeouts вызывается ровно один раз")
    void pollsWhenReadyAndLeader() {
        WaitTimeoutScheduler scheduler = scheduler(() -> true, () -> true);

        scheduler.pollWaitTimeouts();

        verify(executionService, times(1)).checkWaitTimeouts();
    }
}
