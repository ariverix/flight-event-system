package ru.protectinfotrans.eca;

/**
 * Стадии полёта OOOI (Out/Off/On/In) — четыре ключевых момента полёта.
 * INIT — начальная стадия до выкатки от гейта.
 * SUMMARY — финальная стадия после завершения рейса (паритет с SITA: Init/Out/Off/On/In/Summary).
 *
 * Порядок объявления хронологический — операторы сравнения FLIGHT_STAGE (GREATER_THAN и т.д.)
 * полагаются на ordinal().
 */
public enum FlightStage {
    INIT,
    OUT,
    OFF,
    ON,
    IN,
    SUMMARY
}
