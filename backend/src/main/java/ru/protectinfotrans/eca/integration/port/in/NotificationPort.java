package ru.protectinfotrans.eca.integration.port.in;

import ru.protectinfotrans.eca.execution.domain.StepResult;

/**
 * Порт для отправки уведомлений операторам.
 * В MVP реализован через Log-заглушку, в перспективе — WebSocket.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (NotificationPort)
 */
public interface NotificationPort {

    /**
     * Уведомить оператора о результате выполнения шага.
     *
     * @param executionId идентификатор экземпляра выполнения
     * @param stepIndex номер шага
     * @param result результат выполнения
     * @param aircraftId идентификатор ВС
     * @param message текст уведомления
     */
    void notifyStepResult(Long executionId, Integer stepIndex, StepResult result, String aircraftId, String message);
}
