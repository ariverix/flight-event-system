package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Data JPA репозиторий для журнала смен стадии полёта (V29).
 */
public interface FlightStageEventJpaRepository extends JpaRepository<FlightStageEvent, Long> {

    @Query("SELECT MAX(e.occurredAt) FROM FlightStageEvent e " +
           "WHERE e.aircraftId = :aircraftId AND e.stage = :stage")
    Optional<LocalDateTime> findLastStageTimestamp(
            @Param("aircraftId") String aircraftId,
            @Param("stage") FlightStage stage
    );
}
