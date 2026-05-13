package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;

/**
 * Адаптер для публикации событий через Spring ApplicationEventPublisher.
 * Реализует паттерн Transactional Outbox через Spring Modulith Event Publication Registry.
 *
 * См. диплом: раздел 1.4.1 (событийно-ориентированный паттерн, Transactional Outbox)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpringEventPublisherAdapter implements EventPublisherPort {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(NormalizedEvent event) {
        log.debug("Publishing NormalizedEvent: aircraftId={}, messageId={}, stage={}",
                event.aircraftId(), event.messageId(), event.flightStage());

        applicationEventPublisher.publishEvent(event);
    }
}
