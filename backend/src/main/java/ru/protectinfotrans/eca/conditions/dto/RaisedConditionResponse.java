package ru.protectinfotrans.eca.conditions.dto;

import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.time.LocalDateTime;

/** Просмотр активного условия — операторский обзор (RBAC, P3-3). */
public record RaisedConditionResponse(
        Long id,
        String aircraftId,
        String flightNumber,
        String conditionName,
        AlertLevel alertLevel,
        LocalDateTime raisedAt
) {
    public static RaisedConditionResponse from(RaisedCondition condition) {
        return new RaisedConditionResponse(
                condition.getId(),
                condition.getAircraftId(),
                condition.getFlightNumber(),
                condition.getConditionName(),
                condition.getAlertLevel(),
                condition.getRaisedAt()
        );
    }
}
