package ru.protectinfotrans.eca.sequence.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.sequence.dto.*;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;
import ru.protectinfotrans.eca.user.application.UserService;

import java.util.List;

@Tag(name = "Sequences", description = "Управление последовательностями ECA (UC-01..UC-04)")
@RestController
@RequestMapping("/api/v1/sequences")
@RequiredArgsConstructor
public class SequenceController {

    private final SequenceManagementUseCase sequenceUseCase;
    private final UserService userService;

    @Operation(summary = "Создать последовательность",
               description = "UC-01: Создаёт новую последовательность в статусе DRAFT")
    @ApiResponse(responseCode = "201", description = "Последовательность создана")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public SequenceResponse create(@Valid @RequestBody SequenceCreateRequest request, Authentication auth) {
        return sequenceUseCase.createSequence(request, resolveUserId(auth));
    }

    @Operation(summary = "Список последовательностей",
               description = "Получить список всех последовательностей с пагинацией и фильтрацией по статусу")
    @GetMapping
    public PageResponse<SequenceResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return sequenceUseCase.listSequences(page, size, status);
    }

    @Operation(summary = "Получить последовательность",
               description = "Получить последовательность вместе со всеми шагами по ID")
    @ApiResponse(responseCode = "200", description = "Последовательность найдена")
    @ApiResponse(responseCode = "404", description = "Последовательность не найдена")
    @GetMapping("/{id}")
    public SequenceResponse get(@PathVariable Long id) {
        return sequenceUseCase.getSequence(id);
    }

    @Operation(summary = "Обновить последовательность",
               description = "UC-01: Обновить название, описание и критерии (только DRAFT)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SequenceResponse update(@PathVariable Long id,
                                   @Valid @RequestBody SequenceUpdateRequest request,
                                   Authentication auth) {
        return sequenceUseCase.updateSequence(id, request, resolveUserId(auth));
    }

    @Operation(summary = "Удалить последовательность",
               description = "UC-01: Удалить последовательность в статусе DRAFT")
    @ApiResponse(responseCode = "204", description = "Последовательность удалена")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable Long id, Authentication auth) {
        sequenceUseCase.deleteSequence(id, resolveUserId(auth));
    }

    @Operation(summary = "Активировать последовательность",
               description = "UC-04: Перевести в статус ACTIVE — начинает обрабатывать события")
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public SequenceResponse activate(@PathVariable Long id, Authentication auth) {
        return sequenceUseCase.activateSequence(id, resolveUserId(auth));
    }

    @Operation(summary = "Деактивировать последовательность",
               description = "UC-04: Перевести из ACTIVE обратно в DRAFT")
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public SequenceResponse deactivate(@PathVariable Long id, Authentication auth) {
        return sequenceUseCase.deactivateSequence(id, resolveUserId(auth));
    }

    @Operation(summary = "Добавить шаг", tags = {"Steps"},
               description = "UC-02: Добавить шаг типа ACTION, EVALUATE или WAIT")
    @ApiResponse(responseCode = "201", description = "Шаг добавлен")
    @PostMapping("/{id}/steps")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public StepResponse addStep(@PathVariable Long id,
                                @Valid @RequestBody StepCreateRequest request,
                                Authentication auth) {
        return sequenceUseCase.addStep(id, request, resolveUserId(auth));
    }

    @Operation(summary = "Обновить шаг", tags = {"Steps"},
               description = "UC-02/UC-03: Обновить параметры шага и настроить переходы (CONTINUE/GOTO/END/ABORT)")
    @PutMapping("/{id}/steps/{stepId}")
    @PreAuthorize("hasRole('ADMIN')")
    public StepResponse updateStep(@PathVariable Long id,
                                   @PathVariable Long stepId,
                                   @Valid @RequestBody StepUpdateRequest request,
                                   Authentication auth) {
        return sequenceUseCase.updateStep(id, stepId, request, resolveUserId(auth));
    }

    @Operation(summary = "Удалить шаг", tags = {"Steps"},
               description = "UC-02: Удалить шаг из последовательности (только DRAFT)")
    @ApiResponse(responseCode = "204", description = "Шаг удалён")
    @DeleteMapping("/{id}/steps/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteStep(@PathVariable Long id, @PathVariable Long stepId, Authentication auth) {
        sequenceUseCase.deleteStep(id, stepId, resolveUserId(auth));
    }

    @Operation(summary = "Изменить порядок шагов", tags = {"Steps"},
               description = "UC-02: Переупорядочить шаги по списку ID")
    @PutMapping("/{id}/steps/reorder")
    @PreAuthorize("hasRole('ADMIN')")
    public List<StepResponse> reorderSteps(@PathVariable Long id,
                                           @RequestBody List<Long> stepIds,
                                           Authentication auth) {
        return sequenceUseCase.reorderSteps(id, stepIds, resolveUserId(auth));
    }

    private Long resolveUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) return null;
        var user = userService.findByUsername(auth.getName());
        return user != null ? user.getId() : null;
    }
}
