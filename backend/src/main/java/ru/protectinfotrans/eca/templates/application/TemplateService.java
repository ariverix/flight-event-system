package ru.protectinfotrans.eca.templates.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.templates.domain.Template;
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateUpdateRequest;
import ru.protectinfotrans.eca.templates.port.in.TemplateManagementUseCase;
import ru.protectinfotrans.eca.templates.port.out.TemplateRepositoryPort;

import java.util.NoSuchElementException;

/**
 * CRUD-сервис управления шаблонами. Уникальность {@code name} обеспечивается на двух уровнях:
 * предварительная проверка {@code existsByName} здесь (быстрый явный 400/409 без похода до БД-
 * исключения) И уникальный индекс в БД (defense in depth — на случай конкурентной гонки между
 * двумя одновременными запросами создания с одинаковым именем, тот же принцип, что у
 * {@code partial UNIQUE} индекса {@code outbound_messages}, P2-3).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TemplateService implements TemplateManagementUseCase {

    private final TemplateRepositoryPort repository;
    private final TemplateValidator validator;
    private final TemplateRenderer renderer;

    @Override
    public TemplateResponse create(TemplateCreateRequest request) {
        if (repository.existsByName(request.name())) {
            throw new IllegalStateException("Template with name '" + request.name() + "' already exists");
        }
        validator.validateOriginConsistency(request.messageType(), request.origin());

        Template template = Template.builder()
                .name(request.name())
                .description(request.description())
                .messageType(request.messageType())
                .origin(request.origin())
                .category(normalizeCategory(request.category()))
                .body(request.body())
                .active(request.active() == null || request.active())
                .build();

        Template saved = repository.save(template);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateResponse getByName(String name) {
        return toResponse(repository.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + name)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> list(int page, int size, String messageType, String category, Boolean active) {
        MessageType parsedType = messageType != null ? MessageType.valueOf(messageType) : null;
        Page<Template> result = repository.findAll(parsedType, category, active, PageRequest.of(page, size));

        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @Override
    public TemplateResponse update(Long id, TemplateUpdateRequest request) {
        Template template = findOrThrow(id);
        validator.validateOriginConsistency(request.messageType(), request.origin());

        template.setDescription(request.description());
        template.setMessageType(request.messageType());
        template.setOrigin(request.origin());
        template.setCategory(normalizeCategory(request.category()));
        template.setBody(request.body());
        template.setActive(request.active());

        return toResponse(repository.save(template));
    }

    @Override
    public void delete(Long id) {
        findOrThrow(id);
        repository.deleteById(id);
    }

    private Template findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Template not found: " + id));
    }

    private String normalizeCategory(String category) {
        return (category == null || category.isBlank()) ? "GENERAL" : category;
    }

    private TemplateResponse toResponse(Template template) {
        return TemplateResponse.from(template, renderer.extractVariableNames(template.getBody()));
    }
}
