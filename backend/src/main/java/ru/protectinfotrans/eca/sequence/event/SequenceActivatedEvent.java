package ru.protectinfotrans.eca.sequence.event;

/**
 * Доменное событие — последовательность активирована.
 * Публикуется через Spring ApplicationEvents для межмодульного взаимодействия.
 *
 */
public record SequenceActivatedEvent(
        Long sequenceId,
        String startCriteriaJson
) {}
