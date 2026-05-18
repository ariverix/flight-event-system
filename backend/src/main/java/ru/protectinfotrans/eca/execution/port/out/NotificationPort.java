package ru.protectinfotrans.eca.execution.port.out;

/**
 * Выходной порт для отправки уведомлений операторам.
 * В MVP реализован через Log-заглушку, в перспективе — WebSocket.
 *
 * модуля вызывает этот порт, а адаптеры в integration модуле реализуют его.
 *
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