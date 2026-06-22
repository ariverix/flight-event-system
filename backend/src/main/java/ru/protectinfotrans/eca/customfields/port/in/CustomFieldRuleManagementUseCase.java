package ru.protectinfotrans.eca.customfields.port.in;

import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleUpdateRequest;

/** Входной порт CRUD-управления правилами извлечения custom fields — потребляется REST-контроллером. */
public interface CustomFieldRuleManagementUseCase {

    CustomFieldRuleResponse create(CustomFieldRuleCreateRequest request);

    CustomFieldRuleResponse get(Long id);

    PageResponse<CustomFieldRuleResponse> list(int page, int size, String messageType, Boolean active);

    CustomFieldRuleResponse update(Long id, CustomFieldRuleUpdateRequest request);

    void delete(Long id);
}
