package ru.protectinfotrans.eca.execution.domain;

/**
 * Статусы экземпляра выполнения последовательности.
 *
 * См. диплом: раздел 1.3.4 (ключевые сущности — ExecutionInstance)
 */
public enum ExecutionStatus {

    /** Последовательность выполняется */
    RUNNING,

    /** Ожидание реализации критерия (шаг WAIT) */
    WAITING,

    /** Штатное завершение (END) */
    COMPLETED,

    /** Аварийное прерывание (ABORT) */
    ABORTED
}
