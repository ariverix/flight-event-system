package ru.protectinfotrans.eca;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.sequence.dto.PageResponse;

/**
 * REST-контроллер журнала аудита системы.
 * Доступен только администраторам.
 *
 * GET /api/v1/audit-log — список записей с пагинацией и фильтрами.
 *
 * См. диплом: раздел 1.3.4 (AuditLog — журнал аудита), раздел 1.3.5 (UC-09)
 */
@Tag(name = "AuditLog", description = "Журнал аудита операций (только ADMIN)")
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryRepository repository;

    /**
     * GET /api/v1/audit-log
     * Возвращает записи журнала аудита с пагинацией и опциональными фильтрами.
     *
     * @param page       номер страницы (0-based)
     * @param size       размер страницы
     * @param entityType фильтр по типу сущности (SEQUENCE, EXECUTION, USER)
     * @param action     фильтр по типу операции (CREATE_SEQUENCE, USER_LOGIN и т.д.)
     */
    @GetMapping
    @Operation(summary = "Журнал аудита",
               description = "Получить записи журнала аудита с пагинацией (только ADMIN)")
    public PageResponse<AuditLogResponse> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String entityType,
            @RequestParam(required = false)    String action) {

        Pageable pageable = PageRequest.of(page, size);
        Page<AuditLog> result;

        if (entityType != null && action != null) {
            result = repository.findByEntityTypeAndActionOrderByCreatedAtDesc(entityType, action, pageable);
        } else if (entityType != null) {
            result = repository.findByEntityTypeOrderByCreatedAtDesc(entityType, pageable);
        } else if (action != null) {
            result = repository.findByActionOrderByCreatedAtDesc(action, pageable);
        } else {
            result = repository.findAllByOrderByCreatedAtDesc(pageable);
        }

        return new PageResponse<>(
                result.getContent().stream().map(AuditLogResponse::fromEntity).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }
}
