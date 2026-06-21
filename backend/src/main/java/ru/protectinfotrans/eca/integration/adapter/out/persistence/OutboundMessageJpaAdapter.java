package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OutboundMessageJpaAdapter implements OutboundMessageRepositoryPort {

    private final OutboundMessageJpaRepository jpaRepository;

    @Override
    public OutboundMessage save(OutboundMessage message) {
        return jpaRepository.save(message);
    }

    @Override
    public Optional<OutboundMessage> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<OutboundMessage> findByExecutionInstanceIdAndStepOrderIndex(Long executionInstanceId, Integer stepOrderIndex) {
        return jpaRepository.findByExecutionInstanceIdAndStepOrderIndex(executionInstanceId, stepOrderIndex);
    }

    @Override
    public List<OutboundMessage> findPendingCandidates(LocalDateTime now, int limit) {
        List<OutboundMessage> candidates = jpaRepository.findPendingCandidates(now);
        return candidates.size() > limit ? candidates.subList(0, limit) : candidates;
    }

    /**
     * P2-3: явная {@code @Transactional(REQUIRES_NEW)} здесь, по тому же принципу, что
     * {@code ExecutionJpaAdapter#claimExpiredTimeout} (P1-5) — {@code @Modifying} bulk JPQL
     * UPDATE ({@code OutboundMessageJpaRepository#claimPending}) требует АКТИВНОЙ транзакции
     * вокруг {@code EntityManager.executeUpdate}, иначе Hibernate бросает
     * {@code TransactionRequiredException}; стандартный {@code SimpleJpaRepository} транзакцией
     * оборачивает только базовые CRUD-методы, не производные {@code @Query}-методы — границей
     * транзакции выступает этот адаптер (выходной порт), а не repository-интерфейс.
     * {@code REQUIRES_NEW} — claim должен быть собственной, максимально короткой транзакцией:
     * захват условного UPDATE и немедленный коммит сразу освобождает строку для следующего
     * конкурента, не завязываясь на то, есть ли активная транзакция у вызывающего кода
     * (внутри {@code OutboundMessageDeliveryScheduler#deliverOne} транзакция уже есть, но
     * {@code claimPending} вызывается и напрямую из тестов/иных вызывающих без неё).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPending(Long id) {
        return jpaRepository.claimPending(id) == 1;
    }

    @Override
    public void markSent(Long id, LocalDateTime sentAt) {
        jpaRepository.markSent(id, sentAt);
    }

    @Override
    public void markFailed(Long id, String error, int maxAttempts) {
        jpaRepository.markFailed(id, error, maxAttempts);
    }
}
