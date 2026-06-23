package ru.protectinfotrans.eca.conditions.port.in;

import ru.protectinfotrans.eca.FlightStage;

/**
 * Входной порт авто-закрытия активных условий рейса — паритет с SITA Sequencer: "при завершении
 * рейса все активные условия закрываются автоматически" (CLAUDE.md, инвариант). Точный аналог
 * {@code customfields.port.in.FlightContextLifecycleUseCase} (P3-2) — вызывается ИЗ ТОГО ЖЕ канала
 * смены стадии полёта ({@code eventprocessor}, см. {@code MessagePersistenceTransaction
 * #recordFlightStageEvent} и {@code EventProcessorService#notifyFlightStageChange}), сама
 * реализация решает, является ли стадия терминальной.
 */
public interface FlightConditionLifecycleUseCase {

    /**
     * Уведомляет о смене стадии полёта борта/рейса. Если {@code stage} — терминальная
     * ({@link FlightStage#IN} или {@link FlightStage#SUMMARY}), закрывает ВСЕ ещё активные условия
     * этого рейса (см. семантику {@code RaisedCondition#closedAt}); для нетерминальных стадий —
     * no-op.
     *
     * <p>Идемпотентно: повторный вызов с уже закрытым рейсом (например IN, затем SUMMARY) просто
     * не находит активных условий — не является ошибкой.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса — часть per-flight ключа закрываемых условий
     * @param stage новая стадия полёта
     */
    void onFlightStageChanged(String aircraftId, String flightNumber, FlightStage stage);
}
