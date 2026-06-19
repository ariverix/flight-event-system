package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA-адаптер для хранения экземпляров выполнения в PostgreSQL.
 *
 */
@Repository
@RequiredArgsConstructor
public class ExecutionJpaAdapter implements ExecutionRepositoryPort {

    private final ExecutionJpaRepository jpaRepository;

    @Override
    public ExecutionInstance save(ExecutionInstance instance) {
        return jpaRepository.save(instance);
    }

    @Override
    public Optional<ExecutionInstance> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Page<ExecutionInstance> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Page<ExecutionInstance> findByFilters(
            ExecutionStatus status,
            String aircraftId,
            Long sequenceId,
            Pageable pageable
    ) {
        return jpaRepository.findByFilters(status, aircraftId, sequenceId, pageable);
    }

    @Override
    public List<ExecutionInstance> findActiveByAircraftId(String aircraftId) {
        return jpaRepository.findActiveByAircraftId(aircraftId);
    }

    @Override
    public List<ExecutionInstance> findWaitingWithExpiredTimeout(LocalDateTime now) {
        return jpaRepository.findWaitingWithExpiredTimeout(now);
    }

    @Override
    public List<ExecutionInstance> findAllActive() {
        return jpaRepository.findAllActive();
    }
}
