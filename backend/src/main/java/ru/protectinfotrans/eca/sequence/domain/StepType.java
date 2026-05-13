package ru.protectinfotrans.eca.sequence.domain;

/**
 * Три типа шагов последовательности Sequencer.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — 3 типа шагов)
 */
public enum StepType {

    /** Единичное действие (send message, raise/close condition, wait time) */
    ACTION,

    /** Мгновенная проверка критерия — точка ветвления */
    EVALUATE,

    /** Ожидание реализации критерия с таймаутом */
    WAIT
}
