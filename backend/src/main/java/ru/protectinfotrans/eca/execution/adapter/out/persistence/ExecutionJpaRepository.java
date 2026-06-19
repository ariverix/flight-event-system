package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA репозиторий для экземпляров выполнения.
 */
public interface ExecutionJpaRepository extends JpaRepository<ExecutionInstance, Long> {

    Page<ExecutionInstance> findByStatus(ExecutionStatus status, Pageable pageable);

    Page<ExecutionInstance> findByAircraftId(String aircraftId, Pageable pageable);

    Page<ExecutionInstance> findBySequenceId(Long sequenceId, Pageable pageable);

    /**
     * Найти все активные (RUNNING или WAITING) экземпляры для конкретного ВС.
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE e.aircraftId = :aircraftId " +
           "AND e.status IN ('RUNNING', 'WAITING')")
    List<ExecutionInstance> findActiveByAircraftId(@Param("aircraftId") String aircraftId);

    // NOTE: эффективность зависит от индекса (status, wait_timeout_at) на таблице execution_instances
    @Query("SELECT e FROM ExecutionInstance e WHERE e.status = 'WAITING' " +
           "AND e.waitTimeoutAt IS NOT NULL AND e.waitTimeoutAt <= :now")
    List<ExecutionInstance> findWaitingWithExpiredTimeout(@Param("now") LocalDateTime now);

    /**
     * Все незавершённые экземпляры (RUNNING/WAITING) вне зависимости от ВС — для resume при старте (P1-4).
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE e.status IN ('RUNNING', 'WAITING')")
    List<ExecutionInstance> findAllActive();

    /**
     * Универсальный поиск с фильтрацией.
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE " +
           "(:status IS NULL OR e.status = :status) AND " +
           "(:aircraftId IS NULL OR e.aircraftId = :aircraftId) AND " +
           "(:sequenceId IS NULL OR e.sequenceId = :sequenceId)")
    Page<ExecutionInstance> findByFilters(
            @Param("status") ExecutionStatus status,
            @Param("aircraftId") String aircraftId,
            @Param("sequenceId") Long sequenceId,
            Pageable pageable
    );
}
