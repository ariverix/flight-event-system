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
import ru.protectinfotrans.eca.sequence.dto.PageResponse;

/**
 * REST-контроллер для просмотра статуса выполнения последовательностей.
 * Реализует UC-05.
 *
 * См. диплом: раздел 1.3.5 (UC-05 Просмотр статуса выполнения)
 */
@Tag(name = "Execution", description = "Статус выполнения последовательностей")
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionManagementUseCase executionManagement;

    /**
     * UC-05: Получить список экземпляров выполнения с фильтрацией и пагинацией.
     */
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

    /**
     * UC-05: Получить детали экземпляра с историей шагов.
     */
    @Operation(summary = "Детали экземпляра", description = "Получить полную информацию об экземпляре выполнения включая историю шагов")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<ExecutionInstanceResponse> getExecution(@PathVariable Long id) {
        ExecutionInstanceResponse result = executionManagement.getExecution(id);
        return ResponseEntity.ok(result);
    }
}
