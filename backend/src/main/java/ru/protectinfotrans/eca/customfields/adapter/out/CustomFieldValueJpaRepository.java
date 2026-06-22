package ru.protectinfotrans.eca.customfields.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface CustomFieldValueJpaRepository extends JpaRepository<CustomFieldValue, Long> {

    Optional<CustomFieldValue> findByAircraftIdAndFlightNumberAndFieldName(
            String aircraftId, String flightNumber, String fieldName);

    @Query("""
            SELECT v FROM CustomFieldValue v
            WHERE v.aircraftId = :aircraftId
              AND v.flightNumber = :flightNumber
              AND v.closedAt IS NULL
            """)
    List<CustomFieldValue> findActiveByAircraftIdAndFlightNumber(@Param("aircraftId") String aircraftId,
                                                                   @Param("flightNumber") String flightNumber);

    /**
     * Bulk-закрытие всех открытых значений рейса одним UPDATE — паритет с
     * {@code ExecutionJpaRepository#claimExpiredTimeout} (P1-5): атомарная операция на уровне
     * строк БД, не read-modify-write по одной сущности. {@code clearAutomatically = true}
     * предотвращает рассинхронизацию persistence context, если в той же транзакции читались
     * сущности {@code CustomFieldValue} ДО этого UPDATE (тот же приём, что в claim-логике WAIT-
     * таймаутов).
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE CustomFieldValue v
            SET v.closedAt = :closedAt
            WHERE v.aircraftId = :aircraftId
              AND v.flightNumber = :flightNumber
              AND v.closedAt IS NULL
            """)
    int closeAllOpenForFlight(@Param("aircraftId") String aircraftId,
                               @Param("flightNumber") String flightNumber,
                               @Param("closedAt") LocalDateTime closedAt);
}
