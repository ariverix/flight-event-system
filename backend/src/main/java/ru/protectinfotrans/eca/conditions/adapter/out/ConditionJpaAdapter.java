package ru.protectinfotrans.eca.conditions.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.conditions.port.out.ConditionRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ConditionJpaAdapter implements ConditionRepositoryPort {

    private final ConditionJpaRepository jpaRepository;

    @Override
    public Optional<RaisedCondition> findActiveByAircraftIdAndFlightNumberAndConditionName(
            String aircraftId, String flightNumber, String conditionName) {
        return jpaRepository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                aircraftId, flightNumber, conditionName);
    }

    @Override
    public List<RaisedCondition> findActiveByAircraftIdAndFlightNumber(String aircraftId, String flightNumber) {
        return jpaRepository.findActiveByAircraftIdAndFlightNumber(aircraftId, flightNumber);
    }

    @Override
    public RaisedCondition save(RaisedCondition condition) {
        return jpaRepository.save(condition);
    }

    @Override
    public int closeAllActiveForFlight(String aircraftId, String flightNumber, LocalDateTime closedAt) {
        return jpaRepository.closeAllActiveForFlight(aircraftId, flightNumber, closedAt);
    }

    @Override
    public List<RaisedCondition> findAllActive() {
        return jpaRepository.findAllActive();
    }
}
