package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * P1-5: явная {@code @Transactional(REQUIRES_NEW)} здесь, а не "просто" транзакционность
     * Spring Data JPA репозитория по умолчанию — {@code @Modifying} bulk JPQL UPDATE
     * (см. {@code ExecutionJpaRepository#claimExpiredTimeout}) требует АКТИВНОЙ транзакции
     * вокруг {@code EntityManager.executeUpdate}, иначе Hibernate бросает
     * {@code TransactionRequiredException}; стандартный {@code SimpleJpaRepository} оборачивает
     * транзакцией только базовые CRUD-методы, для производных {@code @Query}-методов транзакция
     * не создаётся автоматически без явного {@code @Transactional} на границе вызова — этой
     * границей выступает данный адаптер (выходной порт), а не repository-интерфейс.
     * {@code REQUIRES_NEW} (а не {@code REQUIRED}) — claim должен быть собственной, максимально
     * короткой транзакцией: захват блокировки строки на UPDATE и немедленный коммит сразу же
     * освобождает строку для следующего конкурента, не завязываясь на то, в какой транзакции
     * (если вообще в транзакции) находится вызывающий код ({@code ExecutionService#checkWaitTimeouts}
     * сам не транзакционен — см. его javadoc).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimExpiredTimeout(Long id, LocalDateTime expectedTimeout) {
        return jpaRepository.claimExpiredTimeout(id, expectedTimeout) == 1;
    }

    @Override
    public List<ExecutionInstance> findAllActive() {
        return jpaRepository.findAllActive();
    }

    @Override
    public boolean existsByDedupKey(Long sequenceId, String aircraftId, String flightNumber, Long triggeringMessageId) {
        return jpaRepository.existsBySequenceIdAndAircraftIdAndFlightNumberAndTriggeringMessageId(
                sequenceId, aircraftId, flightNumber, triggeringMessageId);
    }
}
