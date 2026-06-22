package ru.protectinfotrans.eca.templates.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.templates.domain.Template;
import ru.protectinfotrans.eca.templates.port.out.TemplateRepositoryPort;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TemplateJpaAdapter implements TemplateRepositoryPort {

    private final TemplateJpaRepository jpaRepository;

    @Override
    public Template save(Template template) {
        return jpaRepository.save(template);
    }

    @Override
    public Optional<Template> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Template> findByName(String name) {
        return jpaRepository.findByName(name);
    }

    @Override
    public boolean existsByName(String name) {
        return jpaRepository.existsByName(name);
    }

    @Override
    public Page<Template> findAll(MessageType messageType, String category, Boolean active, Pageable pageable) {
        return jpaRepository.findAllFiltered(messageType, category, active, pageable);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }
}
