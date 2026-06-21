package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.port.out.DeadLetterRepositoryPort;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * P2-6: явная {@code @Transactional(REQUIRES_NEW)} на {@code @Modifying} bulk JPQL UPDATE-методах
 * — по тому же принципу, что {@code OutboundMessageJpaAdapter#claimPending} (P2-3) и
 * {@code ExecutionJpaAdapter#claimExpiredTimeout} (P1-5): стандартный {@code SimpleJpaRepository}
 * транзакцией оборачивает только базовые CRUD-методы, не производные {@code @Query}-методы —
 * границей транзакции выступает этот адаптер (выходной порт), а не repository-интерфейс.
 * {@code markReprocessed}/{@code markReprocessFailed} вызываются из
 * {@code DeadLetterQueueService#reprocess}, который сознательно НЕ {@code @Transactional} (см. её
 * javadoc) — без собственной транзакции на адаптере {@code executeUpdate} падает с
 * {@code TransactionRequiredException}. {@code REQUIRES_NEW} делает отметку результата
 * независимой короткой транзакцией, как и описано в javadoc {@code reprocess}.
 */
@Repository
@RequiredArgsConstructor
public class DeadLetterJpaAdapter implements DeadLetterRepositoryPort {

    private final DeadLetterJpaRepository jpaRepository;

    @Override
    public DeadLetterMessage save(DeadLetterMessage message) {
        return jpaRepository.save(message);
    }

    @Override
    public Optional<DeadLetterMessage> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Page<DeadLetterMessage> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Page<DeadLetterMessage> findByStatus(DeadLetterStatus status, Pageable pageable) {
        return jpaRepository.findByStatus(status, pageable);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReprocessed(Long id, Long reprocessedMessageId, LocalDateTime attemptAt) {
        jpaRepository.markReprocessed(id, reprocessedMessageId, attemptAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReprocessFailed(Long id, String reason, String stackTrace, LocalDateTime attemptAt) {
        jpaRepository.markReprocessFailed(id, reason, stackTrace, attemptAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDiscarded(Long id) {
        jpaRepository.markDiscarded(id);
    }
}
