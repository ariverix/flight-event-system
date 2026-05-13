package ru.protectinfotrans.eca.sequence.port.in;

import ru.protectinfotrans.eca.sequence.dto.*;

import java.util.List;

/**
 * Входной порт модуля Sequence Manager — CRUD последовательностей и шагов.
 * Реализует UC-01, UC-02, UC-03, UC-04.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (UserCommandPort)
 */
public interface SequenceManagementUseCase {

    /** UC-01: Создать последовательность */
    SequenceResponse createSequence(SequenceCreateRequest request, Long userId);

    /** UC-01: Получить список последовательностей с пагинацией */
    PageResponse<SequenceResponse> listSequences(int page, int size, String status);

    /** UC-05: Получить последовательность с шагами */
    SequenceResponse getSequence(Long id);

    /** UC-01: Обновить метаданные последовательности */
    SequenceResponse updateSequence(Long id, SequenceUpdateRequest request, Long userId);

    /** UC-01: Удалить последовательность (только DRAFT) */
    void deleteSequence(Long id, Long userId);

    /** UC-04: Активировать последовательность */
    SequenceResponse activateSequence(Long id, Long userId);

    /** UC-04: Деактивировать последовательность */
    SequenceResponse deactivateSequence(Long id, Long userId);

    /** UC-02: Добавить шаг */
    StepResponse addStep(Long sequenceId, StepCreateRequest request, Long userId);

    /** UC-02, UC-03: Обновить шаг (включая настройку переходов) */
    StepResponse updateStep(Long sequenceId, Long stepId, StepUpdateRequest request, Long userId);

    /** UC-02: Удалить шаг */
    void deleteStep(Long sequenceId, Long stepId, Long userId);

    /** UC-02: Изменить порядок шагов */
    List<StepResponse> reorderSteps(Long sequenceId, List<Long> stepIds, Long userId);
}
