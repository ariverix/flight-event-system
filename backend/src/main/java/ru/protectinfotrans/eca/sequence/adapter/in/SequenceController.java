package ru.protectinfotrans.eca.sequence.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.sequence.dto.*;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.util.List;

/**
 * REST-контроллер модуля Sequence Manager.
 * Маппинг: /api/v1/sequences
 *
 * См. диплом: раздел 1.3.5 (UC-01..UC-04),
 *             раздел 1.4.4 (REST-адаптер для UserCommandPort)
 */
@Tag(name = "Sequences", description = "Управление последовательностями ECA (UC-01..UC-04)")
@RestController
@RequestMapping("/api/v1/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final SequenceManagementUseCase sequenceUseCase;

    /** UC-01: Создать последовательность */
    @Operation(summary = "Создать последовательность",
               description = "UC-01: Создаёт новую последовательность в статусе DRAFT")
    @ApiResponse(responseCode = "201", description = "Последовательность создана")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SequenceResponse create(@Valid @RequestBody SequenceCreateRequest request) {
        return sequenceUseCase.createSequence(request, 1L);
    }

    /** UC-05: Список последовательностей с пагинацией */
    @Operation(summary = "Список последовательностей",
               description = "Получить список всех последовательностей с пагинацией и фильтрацией по статусу")
    @GetMapping
    public PageResponse<SequenceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return sequenceUseCase.listSequences(page, size, status);
    }

    /** UC-05: Получить последовательность с шагами */
    @Operation(summary = "Получить последовательность",
               description = "Получить последовательность вместе со всеми шагами по ID")
    @ApiResponse(responseCode = "200", description = "Последовательность найдена")
    @ApiResponse(responseCode = "404", description = "Последовательность не найдена")
    @GetMapping("/{id}")
    public SequenceResponse get(@PathVariable Long id) {
        return sequenceUseCase.getSequence(id);
    }

    /** UC-01: Обновить метаданные последовательности */
    @Operation(summary = "Обновить последовательность",
               description = "UC-01: Обновить название, описание и критерии (только DRAFT)")
    @PutMapping("/{id}")
    public SequenceResponse update(@PathVariable Long id,
                                   @Valid @RequestBody SequenceUpdateRequest request) {
        return sequenceUseCase.updateSequence(id, request, 1L);
    }

    /** UC-01: Удалить последовательность (только DRAFT) */
    @Operation(summary = "Удалить последовательность",
               description = "UC-01: Удалить последовательность в статусе DRAFT")
    @ApiResponse(responseCode = "204", description = "Последовательность удалена")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        sequenceUseCase.deleteSequence(id, 1L);
    }

    /** UC-04: Активировать последовательность */
    @Operation(summary = "Активировать последовательность",
               description = "UC-04: Перевести в статус ACTIVE — начинает обрабатывать события")
    @PostMapping("/{id}/activate")
    public SequenceResponse activate(@PathVariable Long id) {
        return sequenceUseCase.activateSequence(id, 1L);
    }

    /** UC-04: Деактивировать последовательность */
    @Operation(summary = "Деактивировать последовательность",
               description = "UC-04: Перевести из ACTIVE обратно в DRAFT")
    @PostMapping("/{id}/deactivate")
    public SequenceResponse deactivate(@PathVariable Long id) {
        return sequenceUseCase.deactivateSequence(id, 1L);
    }

    /** UC-02: Добавить шаг в последовательность */
    @Operation(summary = "Добавить шаг",
               description = "UC-02: Добавить шаг типа ACTION, EVALUATE или WAIT")
    @ApiResponse(responseCode = "201", description = "Шаг добавлен")
    @PostMapping("/{id}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public StepResponse addStep(@PathVariable Long id,
                                @Valid @RequestBody StepCreateRequest request) {
        return sequenceUseCase.addStep(id, request, 1L);
    }

    /** UC-02, UC-03: Обновить шаг (включая настройку переходов) */
    @Operation(summary = "Обновить шаг",
               description = "UC-02/UC-03: Обновить параметры шага и настроить переходы (CONTINUE/GOTO/END/ABORT)")
    @PutMapping("/{id}/steps/{stepId}")
    public StepResponse updateStep(@PathVariable Long id,
                                   @PathVariable Long stepId,
                                   @Valid @RequestBody StepUpdateRequest request) {
        return sequenceUseCase.updateStep(id, stepId, request, 1L);
    }

    /** UC-02: Удалить шаг */
    @Operation(summary = "Удалить шаг",
               description = "UC-02: Удалить шаг из последовательности (только DRAFT)")
    @ApiResponse(responseCode = "204", description = "Шаг удалён")
    @DeleteMapping("/{id}/steps/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStep(@PathVariable Long id, @PathVariable Long stepId) {
        sequenceUseCase.deleteStep(id, stepId, 1L);
    }

    /** UC-02: Изменить порядок шагов */
    @Operation(summary = "Изменить порядок шагов",
               description = "UC-02: Переупорядочить шаги по списку ID")
    @PutMapping("/{id}/steps/reorder")
    public List<StepResponse> reorderSteps(@PathVariable Long id,
                                           @RequestBody List<Long> stepIds) {
        return sequenceUseCase.reorderSteps(id, stepIds, 1L);
    }
}
