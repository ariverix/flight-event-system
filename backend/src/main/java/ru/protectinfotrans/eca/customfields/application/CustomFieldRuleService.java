package ru.protectinfotrans.eca.customfields.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleUpdateRequest;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldRuleManagementUseCase;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldRuleRepositoryPort;

import java.util.NoSuchElementException;

/**
 * CRUD-сервис управления правилами извлечения. Уникальность {@code name} — тот же
 * двухуровневый принцип, что у {@code TemplateService} (явная проверка + БД unique index defense
 * in depth, V32).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CustomFieldRuleService implements CustomFieldRuleManagementUseCase {

    private final CustomFieldRuleRepositoryPort repository;
    private final CustomFieldRuleValidator validator;

    @Override
    public CustomFieldRuleResponse create(CustomFieldRuleCreateRequest request) {
        if (repository.existsByName(request.name())) {
            throw new IllegalStateException("Custom field rule with name '" + request.name() + "' already exists");
        }
        validator.validatePattern(request.extractionSource(), request.pattern());

        CustomFieldRule rule = CustomFieldRule.builder()
                .name(request.name())
                .description(request.description())
                .messageType(request.messageType())
                .templateName(request.templateName())
                .extractionSource(request.extractionSource())
                .pattern(request.pattern())
                .active(request.active() == null || request.active())
                .build();

        return CustomFieldRuleResponse.from(repository.save(rule));
    }

    @Override
    @Transactional(readOnly = true)
    public CustomFieldRuleResponse get(Long id) {
        return CustomFieldRuleResponse.from(findOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CustomFieldRuleResponse> list(int page, int size, String messageType, Boolean active) {
        MessageType parsedType = messageType != null ? MessageType.valueOf(messageType) : null;
        Page<CustomFieldRule> result = repository.findAll(parsedType, active, PageRequest.of(page, size));

        return new PageResponse<>(
                result.getContent().stream().map(CustomFieldRuleResponse::from).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @Override
    public CustomFieldRuleResponse update(Long id, CustomFieldRuleUpdateRequest request) {
        CustomFieldRule rule = findOrThrow(id);
        validator.validatePattern(request.extractionSource(), request.pattern());

        rule.setDescription(request.description());
        rule.setMessageType(request.messageType());
        rule.setTemplateName(request.templateName());
        rule.setExtractionSource(request.extractionSource());
        rule.setPattern(request.pattern());
        rule.setActive(request.active());

        return CustomFieldRuleResponse.from(repository.save(rule));
    }

    @Override
    public void delete(Long id) {
        findOrThrow(id);
        repository.deleteById(id);
    }

    private CustomFieldRule findOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Custom field rule not found: " + id));
    }
}
