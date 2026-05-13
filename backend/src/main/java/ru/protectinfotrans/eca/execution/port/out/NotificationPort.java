package ru.protectinfotrans.eca.execution.port.out;

/**
 * Выходной порт для отправки уведомлений операторам.
 * В MVP реализован через Log-заглушку, в перспективе — WebSocket.
 *
 * Гексагональная архитектура: это Driven Port (выходной порт) — доменная логика execution
 * модуля вызывает этот порт, а адаптеры в integration модуле реализуют его.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (NotificationPort — выходной порт)
 */
public interface NotificationPort {

    /**
     * Уведомить оператора о результате выполнения шага.
     *
     * @param executionId идентификатор экземпляра выполнения
     * @param stepIndex номер шага
     * @param result результат выполнения ("SUCCESS" или "FAILURE")
     * @param aircraftId идентификатор ВС
     * @param message текст уведомления
     */
    void notifyStepResult(Long executionId, Integer stepIndex, String result, String aircraftId, String message);
}