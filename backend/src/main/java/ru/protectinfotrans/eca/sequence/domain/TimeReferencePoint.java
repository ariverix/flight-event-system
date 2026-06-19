package ru.protectinfotrans.eca.sequence.domain;

/**
 * Опорная точка для критерия TIME_COMPARISON — паритет с SITA Sequencer.
 * ETD/ETA — плановые время вылета/прилёта (из flight data).
 * INIT/OUT/OFF/ON/IN — фактические таймстампы стадий OOOI текущего рейса.
 */
public enum TimeReferencePoint {
    ETD,
    ETA,
    INIT,
    OUT,
    OFF,
    ON,
    IN
}
