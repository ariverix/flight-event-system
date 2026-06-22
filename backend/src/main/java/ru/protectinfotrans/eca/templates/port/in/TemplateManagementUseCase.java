package ru.protectinfotrans.eca.templates.port.in;

import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateUpdateRequest;

/** Входной порт CRUD-управления шаблонами — реализован {@code TemplateService}, потребляется REST-контроллером. */
public interface TemplateManagementUseCase {

    TemplateResponse create(TemplateCreateRequest request);

    TemplateResponse get(Long id);

    TemplateResponse getByName(String name);

    PageResponse<TemplateResponse> list(int page, int size, String messageType, String category, Boolean active);

    TemplateResponse update(Long id, TemplateUpdateRequest request);

    void delete(Long id);
}
