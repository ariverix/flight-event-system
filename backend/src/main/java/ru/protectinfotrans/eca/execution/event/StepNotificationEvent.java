package ru.protectinfotrans.eca.execution.event;

import org.springframework.modulith.events.Externalized;
import ru.protectinfotrans.eca.execution.domain.StepResult;

/**
 * Событие уведомления о результате шага.
 * Публикуется когда onSuccessNotify или onFailureNotify = true.
 *
 * <p>P3-4: несёт {@code sequenceId} и {@code folderId} (immediate, nullable) — этого достаточно
 * модулю {@code eventhandling}, чтобы разрешить обработчики событий уровня последовательности
 * (override) или папки (наследование вверх по дереву папок) БЕЗ обратного запроса в sequence-модуль:
 * родительскую цепочку папок {@code eventhandling} обходит сам (folders — его таблица).
 */
@Externalized("execution.step-notification::#{executionId}-#{stepIndex}")
public record StepNotificationEvent(
        Long executionId,
        Integer stepIndex,
        StepResult result,
        String aircraftId,
        boolean isSuccess,
        Long sequenceId,
        Long folderId
) {
}
