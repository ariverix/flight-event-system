package ru.protectinfotrans.eca;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

// Доступен только администраторам — проверка настроена через SecurityConfig
@Tag(name = "AuditLog", description = "Журнал аудита операций (только ADMIN)")
@RestController
@RequestMapping("/api/v1/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryRepository repository;

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
            result = repository.findByEntityTypeAndActionOrderByIdDesc(entityType, action, pageable);
        } else if (entityType != null) {
            result = repository.findByEntityTypeOrderByIdDesc(entityType, pageable);
        } else if (action != null) {
            result = repository.findByActionOrderByIdDesc(action, pageable);
        } else {
            result = repository.findAllByOrderByIdDesc(pageable);
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
