package ru.protectinfotrans.eca.execution.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Выходной порт для хранения экземпляров выполнения.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (ExecutionRepositoryPort)
 */
public interface ExecutionRepositoryPort {

    ExecutionInstance save(ExecutionInstance instance);

    Optional<ExecutionInstance> findById(Long id);

    Page<ExecutionInstance> findAll(Pageable pageable);

    /**
     * Поиск с фильтрацией по статусу, ВС и последовательности.
     */
    Page<ExecutionInstance> findByFilters(
            ExecutionStatus status,
            String aircraftId,
            Long sequenceId,
            Pageable pageable
    );

    /**
     * Найти все RUNNING/WAITING экземпляры для конкретного ВС.
     * Используется для checkStopCriteria и processWaitingInstances.
     */
    List<ExecutionInstance> findActiveByAircraftId(String aircraftId);

    /**
     * Найти все WAITING экземпляры с истёкшим таймаутом.
     * Используется в @Scheduled checkWaitTimeouts.
     */
    List<ExecutionInstance> findWaitingWithExpiredTimeout(LocalDateTime now);
}
