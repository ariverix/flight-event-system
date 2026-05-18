package ru.protectinfotrans.eca.sequence.domain;

public enum CriterionType {
    MESSAGE_RECEIVED,
    FLIGHT_STAGE,
    POSITION_REPORTED,
    TIME_COMPARISON,
    CONDITION_ACTIVE,
    COMPOUND   // AND/OR над дочерними критериями
}
