package ru.protectinfotrans.eca.sequence.domain;

/**
 * Типы критериев для шагов EVALUATE и WAIT.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Criteria)
 */
public enum CriterionType {

    /** Получено ли сообщение определённого шаблона */
    MESSAGE_RECEIVED,

    /** Проверка стадии полёта */
    FLIGHT_STAGE,

    /** Получен ли позиционный отчёт за последние N минут */
    POSITION_REPORTED,

    /** Сравнение текущего времени с временной отметкой полёта */
    TIME_COMPARISON,

    /** Активно ли пользовательское условие */
    CONDITION_ACTIVE,

    /** Составной критерий (AND/OR) */
    COMPOUND
}
