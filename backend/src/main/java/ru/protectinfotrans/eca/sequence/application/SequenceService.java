package ru.protectinfotrans.eca.sequence.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.dto.*;
import ru.protectinfotrans.eca.sequence.event.SequenceActivatedEvent;
import ru.protectinfotrans.eca.sequence.event.SequenceDeactivatedEvent;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;
import ru.protectinfotrans.eca.sequence.port.out.AuditLogPort;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.util.List;
import java.util.NoSuchElementException;

/** Бизнес-логика для создания, редактирования и активации последовательностей. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SequenceService implements SequenceManagementUseCase {

    private final SequenceRepositoryPort sequenceRepository;
    private final AuditLogPort auditLogPort;
    private final SequenceValidator validator;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public SequenceResponse createSequence(SequenceCreateRequest request, Long userId) {
        Sequence sequence = Sequence.builder()
                .name(request.name())
                .description(request.description())
                .status(SequenceStatus.DRAFT)
                .startCriteriaJson(request.startCriteriaJson())
                .stopCriteriaJson(request.stopCriteriaJson())
                .createdBy(userId)
                .build();

        Sequence saved = sequenceRepository.save(sequence);
        audit(userId, "CREATE_SEQUENCE", "SEQUENCE", saved.getId());
        log.info("Создана последовательность id={} name='{}'", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SequenceResponse> listSequences(int page, int size, String status) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Sequence> result;

        if (status != null && !status.isBlank()) {
            SequenceStatus sequenceStatus = SequenceStatus.valueOf(status.toUpperCase());
            result = sequenceRepository.findByStatus(sequenceStatus, pageRequest);
        } else {
            result = sequenceRepository.findAll(pageRequest);
        }

        List<SequenceResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(content, result.getTotalElements(),
                result.getTotalPages(), result.getNumber(), result.getSize());
    }

    @Override
    @Transactional(readOnly = true)
    public SequenceResponse getSequence(Long id) {
        return toResponse(findSequenceOrThrow(id));
    }

    @Override
    public SequenceResponse updateSequence(Long id, SequenceUpdateRequest request, Long userId) {
        Sequence sequence = findSequenceOrThrow(id);
        requireDraft(sequence);

        sequence.setName(request.name());
        sequence.setDescription(request.description());
        sequence.setStartCriteriaJson(request.startCriteriaJson());
        sequence.setStopCriteriaJson(request.stopCriteriaJson());

        Sequence saved = sequenceRepository.save(sequence);
        audit(userId, "UPDATE_SEQUENCE", "SEQUENCE", saved.getId());
        log.info("Обновлена последовательность id={}", saved.getId());
        return toResponse(saved);
    }

    @Override
    public void deleteSequence(Long id, Long userId) {
        Sequence sequence = findSequenceOrThrow(id);
        requireDraft(sequence);

        sequenceRepository.deleteById(id);
        audit(userId, "DELETE_SEQUENCE", "SEQUENCE", id);
        log.info("Удалена последовательность id={}", id);
    }

    @Override
    public SequenceResponse activateSequence(Long id, Long userId) {
        Sequence sequence = findSequenceOrThrow(id);

        if (sequence.getStatus() != SequenceStatus.DRAFT
                && sequence.getStatus() != SequenceStatus.INACTIVE) {
            throw new IllegalStateException(
                    "Активировать можно только последовательность в статусе DRAFT или INACTIVE");
        }

        List<String> errors = validator.validate(sequence);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Валидация не пройдена: " + String.join("; ", errors));
        }

        sequence.setStatus(SequenceStatus.ACTIVE);
        Sequence saved = sequenceRepository.save(sequence);

        eventPublisher.publishEvent(
                new SequenceActivatedEvent(saved.getId(), saved.getStartCriteriaJson()));

        audit(userId, "ACTIVATE_SEQUENCE", "SEQUENCE", saved.getId());
        log.info("Активирована последовательность id={}", saved.getId());
        return toResponse(saved);
    }

    @Override
    public SequenceResponse deactivateSequence(Long id, Long userId) {
        Sequence sequence = findSequenceOrThrow(id);

        if (sequence.getStatus() != SequenceStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Деактивировать можно только ACTIVE последовательность");
        }

        sequence.setStatus(SequenceStatus.INACTIVE);
        Sequence saved = sequenceRepository.save(sequence);

        eventPublisher.publishEvent(new SequenceDeactivatedEvent(saved.getId()));

        audit(userId, "DEACTIVATE_SEQUENCE", "SEQUENCE", saved.getId());
        log.info("Деактивирована последовательность id={}", saved.getId());
        return toResponse(saved);
    }

    @Override
    public StepResponse addStep(Long sequenceId, StepCreateRequest request, Long userId) {
        Sequence sequence = findSequenceOrThrow(sequenceId);
        requireDraft(sequence);
        validateStepRequest(request.stepType(), request.timeoutSeconds(),
                request.onSuccessAction(), request.onSuccessGotoStep(),
                request.onFailureAction(), request.onFailureGotoStep());

        int nextIndex = sequence.getSteps().stream()
                .mapToInt(Step::getOrderIndex)
                .max()
                .orElse(0) + 1;

        Step step = Step.builder()
                .sequence(sequence)
                .orderIndex(nextIndex)
                .name(request.name())
                .stepType(request.stepType())
                .configJson(request.configJson())
                .timeoutSeconds(request.timeoutSeconds())
                .onSuccessAction(request.onSuccessAction())
                .onSuccessGotoStep(request.onSuccessGotoStep())
                .onSuccessNotify(request.onSuccessNotify() != null ? request.onSuccessNotify() : false)
                .onFailureAction(request.onFailureAction())
                .onFailureGotoStep(request.onFailureGotoStep())
                .onFailureNotify(request.onFailureNotify() != null ? request.onFailureNotify() : false)
                .build();

        sequence.getSteps().add(step);
        Sequence saved = sequenceRepository.save(sequence);

        Step addedStep = saved.getSteps().stream()
                .filter(s -> s.getOrderIndex() == nextIndex)
                .findFirst()
                .orElseThrow();

        audit(userId, "ADD_STEP", "SEQUENCE", sequenceId);
        log.info("Добавлен шаг orderIndex={} в последовательность id={}", nextIndex, sequenceId);
        return toStepResponse(addedStep);
    }

    @Override
    public StepResponse updateStep(Long sequenceId, Long stepId,
                                   StepUpdateRequest request, Long userId) {
        Sequence sequence = findSequenceOrThrow(sequenceId);
        requireDraft(sequence);
        validateStepRequest(request.stepType(), request.timeoutSeconds(),
                request.onSuccessAction(), request.onSuccessGotoStep(),
                request.onFailureAction(), request.onFailureGotoStep());

        Step step = sequence.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Шаг с id=" + stepId + " не найден в последовательности id=" + sequenceId));

        step.setName(request.name());
        step.setStepType(request.stepType());
        step.setConfigJson(request.configJson());
        step.setTimeoutSeconds(request.timeoutSeconds());
        step.setOnSuccessAction(request.onSuccessAction());
        step.setOnSuccessGotoStep(request.onSuccessGotoStep());
        step.setOnSuccessNotify(request.onSuccessNotify() != null ? request.onSuccessNotify() : false);
        step.setOnFailureAction(request.onFailureAction());
        step.setOnFailureGotoStep(request.onFailureGotoStep());
        step.setOnFailureNotify(request.onFailureNotify() != null ? request.onFailureNotify() : false);

        sequenceRepository.save(sequence);
        audit(userId, "UPDATE_STEP", "SEQUENCE", sequenceId);
        log.info("Обновлён шаг id={} в последовательности id={}", stepId, sequenceId);
        return toStepResponse(step);
    }

    @Override
    public void deleteStep(Long sequenceId, Long stepId, Long userId) {
        Sequence sequence = findSequenceOrThrow(sequenceId);
        requireDraft(sequence);

        boolean removed = sequence.getSteps().removeIf(s -> s.getId().equals(stepId));
        if (!removed) {
            throw new NoSuchElementException(
                    "Шаг с id=" + stepId + " не найден в последовательности id=" + sequenceId);
        }

        List<Step> steps = sequence.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).setOrderIndex(i + 1);
        }

        sequenceRepository.save(sequence);
        audit(userId, "DELETE_STEP", "SEQUENCE", sequenceId);
        log.info("Удалён шаг id={} из последовательности id={}", stepId, sequenceId);
    }

    @Override
    public List<StepResponse> reorderSteps(Long sequenceId, List<Long> stepIds, Long userId) {
        Sequence sequence = findSequenceOrThrow(sequenceId);
        requireDraft(sequence);

        List<Step> steps = sequence.getSteps();
        if (stepIds.size() != steps.size()) {
            throw new IllegalArgumentException(
                    "Количество переданных id шагов не совпадает с количеством шагов в последовательности");
        }

        for (int i = 0; i < stepIds.size(); i++) {
            Long targetId = stepIds.get(i);
            Step step = steps.stream()
                    .filter(s -> s.getId().equals(targetId))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                            "Шаг с id=" + targetId + " не найден в последовательности"));
            step.setOrderIndex(i + 1);
        }

        sequenceRepository.save(sequence);
        audit(userId, "REORDER_STEPS", "SEQUENCE", sequenceId);
        log.info("Изменён порядок шагов в последовательности id={}", sequenceId);

        return sequence.getSteps().stream()
                .sorted((a, b) -> Integer.compare(a.getOrderIndex(), b.getOrderIndex()))
                .map(this::toStepResponse)
                .toList();
    }

    private Sequence findSequenceOrThrow(Long id) {
        return sequenceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Последовательность с id=" + id + " не найдена"));
    }

    private void requireDraft(Sequence sequence) {
        if (sequence.getStatus() != SequenceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Редактирование возможно только для последовательности в статусе DRAFT");
        }
    }

    private void validateStepRequest(ru.protectinfotrans.eca.sequence.domain.StepType stepType,
                                     Integer timeoutSeconds,
                                     ru.protectinfotrans.eca.sequence.domain.TransitionAction onSuccessAction,
                                     Integer onSuccessGotoStep,
                                     ru.protectinfotrans.eca.sequence.domain.TransitionAction onFailureAction,
                                     Integer onFailureGotoStep) {
        if (stepType == ru.protectinfotrans.eca.sequence.domain.StepType.WAIT) {
            if (timeoutSeconds == null || timeoutSeconds <= 0) {
                throw new IllegalArgumentException("WAIT-шаг должен иметь таймаут > 0");
            }
        }
        if (onSuccessAction == ru.protectinfotrans.eca.sequence.domain.TransitionAction.GOTO
                && onSuccessGotoStep == null) {
            throw new IllegalArgumentException(
                    "onSuccessGotoStep обязателен при onSuccessAction = GOTO");
        }
        if (onFailureAction == ru.protectinfotrans.eca.sequence.domain.TransitionAction.GOTO
                && onFailureGotoStep == null) {
            throw new IllegalArgumentException(
                    "onFailureGotoStep обязателен при onFailureAction = GOTO");
        }
    }

    private void audit(Long userId, String action, String entityType, Long entityId) {
        auditLogPort.save(AuditLog.builder()
                .userId(userId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .build());
    }

    private SequenceResponse toResponse(Sequence sequence) {
        List<StepResponse> stepResponses = sequence.getSteps().stream()
                .map(this::toStepResponse)
                .toList();

        return new SequenceResponse(
                sequence.getId(),
                sequence.getName(),
                sequence.getDescription(),
                sequence.getStatus(),
                sequence.getStartCriteriaJson(),
                sequence.getStopCriteriaJson(),
                stepResponses,
                sequence.getCreatedAt(),
                sequence.getUpdatedAt(),
                sequence.getCreatedBy()
        );
    }

    private StepResponse toStepResponse(Step step) {
        return new StepResponse(
                step.getId(),
                step.getOrderIndex(),
                step.getName(),
                step.getStepType(),
                step.getConfigJson(),
                step.getTimeoutSeconds(),
                step.getOnSuccessAction(),
                step.getOnSuccessGotoStep(),
                step.getOnSuccessNotify(),
                step.getOnFailureAction(),
                step.getOnFailureGotoStep(),
                step.getOnFailureNotify()
        );
    }
}
