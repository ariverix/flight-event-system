package ru.protectinfotrans.eca.sequence.domain;

/**
 * Операторы сравнения для критериев FLIGHT_STAGE и TIME_COMPARISON.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Criteria)
 */
public enum ComparisonOperator {

    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    LESS_THAN,
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL
}
