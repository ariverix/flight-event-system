package ru.protectinfotrans.eca.sequence.port.in;

import ru.protectinfotrans.eca.sequence.dto.*;

import java.util.List;

public interface SequenceManagementUseCase {

    SequenceResponse createSequence(SequenceCreateRequest request, Long userId);

    PageResponse<SequenceResponse> listSequences(int page, int size, String status);

    SequenceResponse getSequence(Long id);

    SequenceResponse updateSequence(Long id, SequenceUpdateRequest request, Long userId);

    void deleteSequence(Long id, Long userId);

    SequenceResponse activateSequence(Long id, Long userId);

    SequenceResponse deactivateSequence(Long id, Long userId);

    StepResponse addStep(Long sequenceId, StepCreateRequest request, Long userId);

    StepResponse updateStep(Long sequenceId, Long stepId, StepUpdateRequest request, Long userId);

    void deleteStep(Long sequenceId, Long stepId, Long userId);

    List<StepResponse> reorderSteps(Long sequenceId, List<Long> stepIds, Long userId);
}
