package ru.protectinfotrans.eca.cluster;

/**
 * P6-1: контракт leader election для гейтинга кластерных фоновых задач (@Scheduled-поллеров).
 *
 * <p>Публичный API модуля {@code cluster} (Spring Modulith): на него зависят планировщики из
 * модулей {@code execution} (WaitTimeoutScheduler) и {@code integration}
 * (OutboundMessageDeliveryScheduler), чтобы автоматический {@code @Scheduled}-тик выполнялся ТОЛЬКО
 * на одной реплике-лидере — без избыточного параллельного опроса всеми репликами.
 *
 * <p><b>Корректность vs эффективность:</b> {@code isLeader()} — это оптимизация (кто опрашивает),
 * а НЕ механизм корректности. Single-fire (ровно одно срабатывание таймаута/доставки) гарантируется
 * атомарным DB-claim'ом в самих планировщиках (P1-5/P2-3) и работает даже без leader election —
 * поэтому кратковременное «раздвоение» лидерства безопасно.
 */
public interface LeaderElection {

    /**
     * @return {@code true}, если ЭТА реплика сейчас держит валидную (не протухшую) аренду лидерства.
     *         Может кратковременно быть {@code true} на двух репликах при переходе аренды — это
     *         безопасно (см. javadoc интерфейса: defense-in-depth DB-claim).
     */
    boolean isLeader();
}
