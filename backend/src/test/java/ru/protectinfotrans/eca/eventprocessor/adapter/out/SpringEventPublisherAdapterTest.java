package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;

/**
 * Unit-тест для SpringEventPublisherAdapter.
 * Проверяет делегирование публикации события к ApplicationEventPublisher.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpringEventPublisherAdapter")
class SpringEventPublisherAdapterTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private SpringEventPublisherAdapter adapter;

    @Test
    @DisplayName("publish: должен делегировать публикацию события в ApplicationEventPublisher")
    void shouldPublishNormalizedEvent() {
        var event = new NormalizedEvent(
                1L, MessageType.DOWNLINK, "POSITION", "RA-1234", "SU100",
                FlightStage.OUT, LocalDateTime.now());

        adapter.publish(event);

        verify(applicationEventPublisher).publishEvent(event);
    }
}
