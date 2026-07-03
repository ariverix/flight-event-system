package ru.protectinfotrans.eca.cluster;

/**
 * P2-3 (гигиена): контракт готовности приложения для гейтинга {@code @Scheduled}-поллеров.
 *
 * <p>Публичный API модуля {@code cluster} (Spring Modulith), по аналогии с {@link LeaderElection}:
 * планировщики из модулей {@code execution} (WaitTimeoutScheduler), {@code integration}
 * (OutboundMessageDeliveryScheduler) и корневой {@code RetentionService} гейтят автоматический
 * тик по {@code isReady()}, чтобы фоновые задачи не тикали до полной готовности приложения
 * ({@code ApplicationReadyEvent}: контекст поднят, Flyway отработал, пулы прогреты).
 *
 * <p>Функциональный интерфейс — в тестах планировщиков можно передать заглушку {@code () -> true}
 * (тот же приём, что и для {@link LeaderElection}). Продакшн-реализация — {@link ApplicationReadinessGate}.
 */
@FunctionalInterface
public interface ApplicationReadiness {

    /**
     * @return {@code true}, если приложение получило {@code ApplicationReadyEvent} и готово
     *         выполнять фоновые задачи; {@code false} до этого момента.
     */
    boolean isReady();
}
