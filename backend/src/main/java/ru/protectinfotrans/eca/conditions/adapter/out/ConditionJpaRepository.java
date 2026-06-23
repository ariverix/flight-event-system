package ru.protectinfotrans.eca.conditions.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface ConditionJpaRepository extends JpaRepository<RaisedCondition, Long> {

    @Query("""
            SELECT c FROM RaisedCondition c
            WHERE c.aircraftId = :aircraftId
              AND c.flightNumber = :flightNumber
              AND c.conditionName = :conditionName
              AND c.closedAt IS NULL
            """)
    Optional<RaisedCondition> findActiveByAircraftIdAndFlightNumberAndConditionName(
            @Param("aircraftId") String aircraftId,
            @Param("flightNumber") String flightNumber,
            @Param("conditionName") String conditionName);

    @Query("""
            SELECT c FROM RaisedCondition c
            WHERE c.aircraftId = :aircraftId
              AND c.flightNumber = :flightNumber
              AND c.closedAt IS NULL
            """)
    List<RaisedCondition> findActiveByAircraftIdAndFlightNumber(@Param("aircraftId") String aircraftId,
                                                                  @Param("flightNumber") String flightNumber);

    /**
     * Bulk-закрытие всех активных условий рейса одним UPDATE — паритет с
     * {@code CustomFieldValueJpaRepository#closeAllOpenForFlight} (P3-2), тот же приём:
     * атомарная операция на уровне строк БД, {@code clearAutomatically = true} предотвращает
     * рассинхронизацию persistence context.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE RaisedCondition c
            SET c.closedAt = :closedAt
            WHERE c.aircraftId = :aircraftId
              AND c.flightNumber = :flightNumber
              AND c.closedAt IS NULL
            """)
    int closeAllActiveForFlight(@Param("aircraftId") String aircraftId,
                                 @Param("flightNumber") String flightNumber,
                                 @Param("closedAt") LocalDateTime closedAt);

    @Query("SELECT c FROM RaisedCondition c WHERE c.closedAt IS NULL ORDER BY c.raisedAt DESC")
    List<RaisedCondition> findAllActive();
}
