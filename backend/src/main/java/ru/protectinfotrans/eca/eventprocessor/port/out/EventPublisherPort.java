package ru.protectinfotrans.eca.eventprocessor.port.out;

import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;

/**
 * Выходной порт для публикации нормализованных событий.
 *
 * модуля вызывает этот порт для публикации событий, а адаптеры реализуют его
 * (через Spring ApplicationEventPublisher).
 *
 */
public interface EventPublisherPort {

    /**
     * Опубликовать нормализованное событие.
     * Событие будет доставлено всем слушателям через Spring Modulith Event Publication Registry.
     *
     * @param event нормализованное событие
     */
    void publish(NormalizedEvent event);
}
