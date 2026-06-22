package ru.protectinfotrans.eca.customfields.port.in;

import ru.protectinfotrans.eca.FlightStage;

/**
 * Входной порт закрытия per-flight контекста custom fields — паритет с SITA Sequencer: "при
 * завершении рейса контекст custom fields закрывается" (CLAUDE.md, инвариант). Вызывается
 * {@code eventprocessor} (см. {@code EventProcessorService#notifyFlightStageChange} и обработку
 * стадии, разобранной из ARINC 618 OOOI-меток входящего сообщения) на каждую смену стадии — сама
 * реализация решает, является ли стадия терминальной.
 */
public interface FlightContextLifecycleUseCase {

    /**
     * Уведомляет о смене стадии полёта борта/рейса. Если {@code stage} — терминальная
     * ({@link FlightStage#IN} или {@link FlightStage#SUMMARY}), закрывает (см. семантику
     * {@code CustomFieldValue#closedAt}) ВСЕ ещё открытые custom field значения этого рейса; для
     * нетерминальных стадий — no-op (контекст остаётся открытым для дальнейшего накопления
     * значений по ходу рейса).
     *
     * <p>Идемпотентно: повторный вызов с уже закрытым рейсом (например IN, затем SUMMARY) просто
     * не находит открытых строк для закрытия — не является ошибкой.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса — часть per-flight ключа закрываемых значений
     * @param stage новая стадия полёта
     */
    void onFlightStageChanged(String aircraftId, String flightNumber, FlightStage stage);
}
