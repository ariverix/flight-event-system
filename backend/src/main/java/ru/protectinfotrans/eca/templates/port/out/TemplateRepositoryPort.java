package ru.protectinfotrans.eca.templates.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.templates.domain.Template;

import java.util.Optional;

/** Выходной порт персистентности шаблонов — реализуется JPA-адаптером в {@code adapter.out}. */
public interface TemplateRepositoryPort {

    Template save(Template template);

    Optional<Template> findById(Long id);

    Optional<Template> findByName(String name);

    boolean existsByName(String name);

    Page<Template> findAll(MessageType messageType, String category, Boolean active, Pageable pageable);

    void deleteById(Long id);
}
