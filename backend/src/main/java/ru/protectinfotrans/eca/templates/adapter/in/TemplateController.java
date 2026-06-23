package ru.protectinfotrans.eca.templates.adapter.in;

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
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateRenderRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateRenderResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateUpdateRequest;
import ru.protectinfotrans.eca.templates.port.in.TemplateManagementUseCase;
import ru.protectinfotrans.eca.templates.port.in.TemplateRenderUseCase;

/**
 * CRUD-управление шаблонами + пробный рендеринг (предпросмотр в UI). Управление шаблонами —
 * админ/оператор операция (RBAC), НЕ открытый эндпоинт — см. {@code SecurityConfig}, явное
 * правило {@code /api/v1/templates/**} добавлено ДО catch-all {@code anyRequest().permitAll()}.
 */
@Tag(name = "Templates", description = "Управление шаблонами сообщений (downlink/uplink/ground, P3-1)")
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateManagementUseCase templateUseCase;
    private final TemplateRenderUseCase renderUseCase;

    @Operation(summary = "Создать шаблон",
               description = "Создаёт шаблон сообщения (downlink/uplink/ground). "
                       + "Для uplink/ground origin обязателен (computer-generated|external-user).")
    @ApiResponse(responseCode = "201", description = "Шаблон создан")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MANAGE_TEMPLATES')")
    public TemplateResponse create(@Valid @RequestBody TemplateCreateRequest request) {
        return templateUseCase.create(request);
    }

    @Operation(summary = "Список шаблонов",
               description = "Список шаблонов с пагинацией, опциональной фильтрацией по типу/категории/активности")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_TEMPLATES')")
    public PageResponse<TemplateResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String messageType,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean active) {
        return templateUseCase.list(page, size, messageType, category, active);
    }

    @Operation(summary = "Получить шаблон по ID")
    @ApiResponse(responseCode = "200", description = "Шаблон найден")
    @ApiResponse(responseCode = "404", description = "Шаблон не найден")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_TEMPLATES')")
    public TemplateResponse get(@PathVariable Long id) {
        return templateUseCase.get(id);
    }

    @Operation(summary = "Получить шаблон по имени",
               description = "Используется для предпросмотра/проверки шаблона, на который ссылается ACTION-шаг или критерий")
    @GetMapping("/by-name/{name}")
    @PreAuthorize("hasAuthority('VIEW_TEMPLATES')")
    public TemplateResponse getByName(@PathVariable String name) {
        return templateUseCase.getByName(name);
    }

    @Operation(summary = "Обновить шаблон",
               description = "Обновляет тело/категорию/origin/активность. Имя неизменяемо.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MANAGE_TEMPLATES')")
    public TemplateResponse update(@PathVariable Long id, @Valid @RequestBody TemplateUpdateRequest request) {
        return templateUseCase.update(id, request);
    }

    @Operation(summary = "Удалить шаблон")
    @ApiResponse(responseCode = "204", description = "Шаблон удалён")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MANAGE_TEMPLATES')")
    public void delete(@PathVariable Long id) {
        templateUseCase.delete(id);
    }

    @Operation(summary = "Пробный рендеринг шаблона",
               description = "Подставляет переданные переменные в тело шаблона — предпросмотр в UI перед сохранением ACTION-шага")
    @PostMapping("/render")
    @PreAuthorize("hasAuthority('VIEW_TEMPLATES')")
    public TemplateRenderResponse render(@Valid @RequestBody TemplateRenderRequest request) {
        String rendered = renderUseCase.render(request.templateName(), request.variables());
        return new TemplateRenderResponse(request.templateName(), rendered);
    }
}
