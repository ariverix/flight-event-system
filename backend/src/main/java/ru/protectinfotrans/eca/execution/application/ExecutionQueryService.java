package ru.protectinfotrans.eca.execution.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepExecution;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.execution.dto.StepExecutionResponse;
import ru.protectinfotrans.eca.execution.port.in.ExecutionManagementUseCase;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.sequence.dto.PageResponse;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Сервис для запросов данных о выполнении последовательностей.
 *
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ExecutionQueryService implements ExecutionManagementUseCase {

    private final ExecutionRepositoryPort executionRepository;
    private final SequenceQueryPort sequenceQuery;

    @Override
    public PageResponse<ExecutionInstanceResponse> listExecutions(
            int page,
            int size,
            ExecutionStatus status,
            String aircraftId,
            Long sequenceId
    ) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("startedAt").descending());
        Page<ExecutionInstance> result = executionRepository.findByFilters(status, aircraftId, sequenceId, pageRequest);

        List<ExecutionInstanceResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(
                content,
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
    }

    @Override
    public ExecutionInstanceResponse getExecution(Long id) {
        ExecutionInstance instance = executionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Execution instance not found: " + id));
        return toResponse(instance);
    }

    private ExecutionInstanceResponse toResponse(ExecutionInstance instance) {
        List<StepExecutionResponse> steps = instance.getStepHistory().stream()
                .map(this::toStepResponse)
                .toList();

        String sequenceName = sequenceQuery.findById(instance.getSequenceId())
                .map(s -> s.getName())
                .orElse("—");

        return new ExecutionInstanceResponse(
                instance.getId(),
                instance.getSequenceId(),
                sequenceName,
                instance.getAircraftId(),
                instance.getFlightNumber(),
                instance.getStatus(),
                instance.getCurrentStepIndex(),
                instance.getContextJson(),
                instance.getWaitStartedAt(),
                instance.getWaitTimeoutAt(),
                instance.getStartedAt(),
                instance.getCompletedAt(),
                steps
        );
    }

    private StepExecutionResponse toStepResponse(StepExecution step) {
        return new StepExecutionResponse(
                step.getId(),
                step.getStepIndex(),
                step.getStepType(),
                step.getResult(),
                step.getTransitionAction(),
                step.getTransitionTarget(),
                step.getDetailsJson(),
                step.getExecutedAt()
        );
    }
}
