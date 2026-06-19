package ru.protectinfotrans.eca.sequence.domain;

/**
 * Источник позиционного отчёта — паритет с SITA Sequencer.
 * Оценочные (estimated) позиции из любого источника игнорируются POSITION-критерием —
 * см. {@link ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage#isEstimatedPosition()}.
 */
public enum PositionSource {
    ACARS,
    RADAR,
    ADS_B
}
