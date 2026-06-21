package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;
import ru.protectinfotrans.eca.integration.port.out.CallsignMatchingRepositoryPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P2-4 (часть 1 — схема): JPA-адаптер хранения правил соответствия позывных FI. Только
 * персистентность — алгоритм матчинга (выбор кандидата по specificity, проверка дня недели и
 * т.д.) реализует часть 2 (integration-dev) поверх {@link #findCandidates}.
 */
@Repository
@RequiredArgsConstructor
public class CallsignMatchingJpaAdapter implements CallsignMatchingRepositoryPort {

    private final CallsignMatchingJpaRepository jpaRepository;

    @Override
    public CallsignMatchingRule save(CallsignMatchingRule rule) {
        return jpaRepository.save(rule);
    }

    @Override
    public Optional<CallsignMatchingRule> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<CallsignMatchingRule> findCandidates(String icaoCarrierCode, LocalDate onDate) {
        return jpaRepository.findCandidates(icaoCarrierCode, onDate);
    }
}
