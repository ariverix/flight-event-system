package ru.protectinfotrans.eca.sequence.adapter.in;

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
@RestController
@RequestMapping("/api/v1/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final SequenceManagementUseCase sequenceUseCase;

    /** UC-01: Создать последовательность */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SequenceResponse create(@Valid @RequestBody SequenceCreateRequest request) {
        // TODO: получать userId из SecurityContext после Step 7
        return sequenceUseCase.createSequence(request, 1L);
    }

    /** UC-05: Список последовательностей с пагинацией */
    @GetMapping
    public PageResponse<SequenceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return sequenceUseCase.listSequences(page, size, status);
    }

    /** UC-05: Получить последовательность с шагами */
    @GetMapping("/{id}")
    public SequenceResponse get(@PathVariable Long id) {
        return sequenceUseCase.getSequence(id);
    }

    /** UC-01: Обновить метаданные последовательности */
    @PutMapping("/{id}")
    public SequenceResponse update(@PathVariable Long id,
                                   @Valid @RequestBody SequenceUpdateRequest request) {
        return sequenceUseCase.updateSequence(id, request, 1L);
    }

    /** UC-01: Удалить последовательность (только DRAFT) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        sequenceUseCase.deleteSequence(id, 1L);
    }

    /** UC-04: Активировать последовательность */
    @PostMapping("/{id}/activate")
    public SequenceResponse activate(@PathVariable Long id) {
        return sequenceUseCase.activateSequence(id, 1L);
    }

    /** UC-04: Деактивировать последовательность */
    @PostMapping("/{id}/deactivate")
    public SequenceResponse deactivate(@PathVariable Long id) {
        return sequenceUseCase.deactivateSequence(id, 1L);
    }

    /** UC-02: Добавить шаг в последовательность */
    @PostMapping("/{id}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    public StepResponse addStep(@PathVariable Long id,
                                @Valid @RequestBody StepCreateRequest request) {
        return sequenceUseCase.addStep(id, request, 1L);
    }

    /** UC-02, UC-03: Обновить шаг (включая настройку переходов) */
    @PutMapping("/{id}/steps/{stepId}")
    public StepResponse updateStep(@PathVariable Long id,
                                   @PathVariable Long stepId,
                                   @Valid @RequestBody StepUpdateRequest request) {
        return sequenceUseCase.updateStep(id, stepId, request, 1L);
    }

    /** UC-02: Удалить шаг */
    @DeleteMapping("/{id}/steps/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStep(@PathVariable Long id, @PathVariable Long stepId) {
        sequenceUseCase.deleteStep(id, stepId, 1L);
    }

    /** UC-02: Изменить порядок шагов */
    @PutMapping("/{id}/steps/reorder")
    public List<StepResponse> reorderSteps(@PathVariable Long id,
                                           @RequestBody List<Long> stepIds) {
        return sequenceUseCase.reorderSteps(id, stepIds, 1L);
    }
}
