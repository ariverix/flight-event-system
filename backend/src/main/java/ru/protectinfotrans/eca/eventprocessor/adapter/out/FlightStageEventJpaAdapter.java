package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.FlightStageEventRepositoryPort;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class FlightStageEventJpaAdapter implements FlightStageEventRepositoryPort {

    private final FlightStageEventJpaRepository jpaRepository;

    @Override
    public FlightStageEvent save(FlightStageEvent event) {
        return jpaRepository.save(event);
    }

    @Override
    public Optional<LocalDateTime> findLastStageTimestamp(String aircraftId, FlightStage stage) {
        return jpaRepository.findLastStageTimestamp(aircraftId, stage);
    }
}
