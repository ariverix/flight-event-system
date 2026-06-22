package ru.protectinfotrans.eca.customfields.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleUpdateRequest;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldRuleManagementUseCase;

/**
 * CRUD-управление правилами извлечения custom fields — админ/оператор операция (RBAC), НЕ
 * открытый эндпоинт (см. {@code SecurityConfig}, правило {@code /api/v1/custom-field-rules/**}
 * добавлено ДО catch-all {@code anyRequest().permitAll()}, тот же принцип, что у
 * {@code /api/v1/templates/**}, P3-1).
 */
@Tag(name = "Custom Field Rules", description = "Управление правилами извлечения custom fields из входящих сообщений (P3-2)")
@RestController
@RequestMapping("/api/v1/custom-field-rules")
@RequiredArgsConstructor
public class CustomFieldRuleController {

    private final CustomFieldRuleManagementUseCase ruleUseCase;

    @Operation(summary = "Создать правило извлечения",
               description = "Заводит правило извлечения custom field из входящих сообщений заданного типа "
                       + "(опционально шаблона) по regex (CONTENT) или ключу metadata (METADATA).")
    @ApiResponse(responseCode = "201", description = "Правило создано")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public CustomFieldRuleResponse create(@Valid @RequestBody CustomFieldRuleCreateRequest request) {
        return ruleUseCase.create(request);
    }

    @Operation(summary = "Список правил", description = "Список правил с пагинацией, опциональной фильтрацией по типу сообщения/активности")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public PageResponse<CustomFieldRuleResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String messageType,
            @RequestParam(required = false) Boolean active) {
        return ruleUseCase.list(page, size, messageType, active);
    }

    @Operation(summary = "Получить правило по ID")
    @ApiResponse(responseCode = "200", description = "Правило найдено")
    @ApiResponse(responseCode = "404", description = "Правило не найдено")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public CustomFieldRuleResponse get(@PathVariable Long id) {
        return ruleUseCase.get(id);
    }

    @Operation(summary = "Обновить правило", description = "Обновляет описание/тип сообщения/шаблон/паттерн/активность. Имя неизменяемо.")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public CustomFieldRuleResponse update(@PathVariable Long id, @Valid @RequestBody CustomFieldRuleUpdateRequest request) {
        return ruleUseCase.update(id, request);
    }

    @Operation(summary = "Удалить правило")
    @ApiResponse(responseCode = "204", description = "Правило удалено")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id) {
        ruleUseCase.delete(id);
    }
}
