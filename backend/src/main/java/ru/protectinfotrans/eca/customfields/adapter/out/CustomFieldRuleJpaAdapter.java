package ru.protectinfotrans.eca.customfields.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldRuleRepositoryPort;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CustomFieldRuleJpaAdapter implements CustomFieldRuleRepositoryPort {

    private final CustomFieldRuleJpaRepository jpaRepository;

    @Override
    public CustomFieldRule save(CustomFieldRule rule) {
        return jpaRepository.save(rule);
    }

    @Override
    public Optional<CustomFieldRule> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public boolean existsByName(String name) {
        return jpaRepository.existsByName(name);
    }

    @Override
    public Page<CustomFieldRule> findAll(MessageType messageType, Boolean active, Pageable pageable) {
        return jpaRepository.findAllFiltered(messageType, active, pageable);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public List<CustomFieldRule> findActiveApplicableRules(MessageType messageType, String templateName) {
        return jpaRepository.findActiveApplicableRules(messageType, templateName);
    }
}
