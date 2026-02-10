package ru.protectinfotrans.eca.sequence.domain;

/**
 * Действия Result Decision Maker — управление потоком после завершения шага.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Result Decision Maker)
 */
public enum TransitionAction {

    /** Перейти к следующему шагу по порядку */
    CONTINUE,

    /** Перейти к указанному шагу (по номеру) */
    GOTO,

    /** Завершить штатно (нормальное окончание) */
    END,

    /** Прервать аварийно */
    ABORT
}
