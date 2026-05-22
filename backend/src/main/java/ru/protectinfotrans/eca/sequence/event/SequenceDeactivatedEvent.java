package ru.protectinfotrans.eca.sequence.event;

/**
 * Доменное событие — последовательность деактивирована.
 * Публикуется через Spring ApplicationEvents для межмодульного взаимодействия.
 *
 */
public record SequenceDeactivatedEvent(
        Long sequenceId
) {}
