package ru.protectinfotrans.eca.execution.domain;

/**
 * Тип события Tracking Event Log (P1-8, V24) — SITA-класс журнала "Event Log класса
 * Tracking": старт/стоп последовательности и завершение шагов.
 *
 * <p>{@code SEQUENCE_STOPPED} соответствует штатному завершению инстанса
 * ({@link ExecutionStatus#COMPLETED}), {@code SEQUENCE_ABORTED} — терминации через
 * decision ABORT ({@link ExecutionStatus#ABORTED}, см. P1-1/P1-2). Различаются, чтобы
 * оператор в журнале видел причину остановки, а не только факт.
 */
public enum TrackingEventType {
    SEQUENCE_STARTED,
    SEQUENCE_STOPPED,
    SEQUENCE_ABORTED,
    STEP_COMPLETED
}
