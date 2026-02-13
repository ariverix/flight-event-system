package ru.protectinfotrans.eca.sequence.event;

/**
 * Доменное событие — последовательность деактивирована.
 * Публикуется через Spring ApplicationEvents для межмодульного взаимодействия.
 *
 * См. диплом: раздел 1.3.5 (UC-04 Активировать последовательность),
 *             раздел 1.4.1 (событийно-ориентированная архитектура)
 */
public record SequenceDeactivatedEvent(
        Long sequenceId
) {}
