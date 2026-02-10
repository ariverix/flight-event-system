package ru.protectinfotrans.eca.execution.domain;

/**
 * Результат выполнения шага — определяет какой Result Decision Maker будет применён.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Result Decision Maker)
 */
public enum StepResult {

    /** Шаг завершился успешно (Successful / True) */
    SUCCESS,

    /** Шаг завершился неуспешно (Failed / False) */
    FAILURE
}
