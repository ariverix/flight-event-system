package ru.protectinfotrans.eca.execution.adapter.in.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.execution.port.in.ExecutionManagementUseCase;
import ru.protectinfotrans.eca.PageResponse;

@Tag(name = "Executions", description = "Статус выполнения последовательностей (UC-05)")
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionManagementUseCase executionManagement;

    @Operation(summary = "Список экземпляров выполнения", description = "Получить список всех экземпляров с фильтрацией по статусу, ВС и последовательности")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<PageResponse<ExecutionInstanceResponse>> listExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) ExecutionStatus status,
            @RequestParam(required = false) String aircraftId,
            @RequestParam(required = false) Long sequenceId
    ) {
        PageResponse<ExecutionInstanceResponse> result = executionManagement.listExecutions(
                page, size, status, aircraftId, sequenceId
        );
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Детали экземпляра", description = "Получить полную информацию об экземпляре выполнения включая историю шагов")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<ExecutionInstanceResponse> getExecution(@PathVariable Long id) {
        ExecutionInstanceResponse result = executionManagement.getExecution(id);
        return ResponseEntity.ok(result);
    }
}
