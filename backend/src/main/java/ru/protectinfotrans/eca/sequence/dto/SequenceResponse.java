package ru.protectinfotrans.eca.sequence.dto;

import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ответ с данными последовательности.
 * См. диплом: раздел 1.3.5 (UC-05 Просмотреть статус выполнения)
 */
public record SequenceResponse(
        Long id,
        String name,
        String description,
        SequenceStatus status,
        String startCriteriaJson,
        String stopCriteriaJson,
        List<StepResponse> steps,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long createdBy
) {}
