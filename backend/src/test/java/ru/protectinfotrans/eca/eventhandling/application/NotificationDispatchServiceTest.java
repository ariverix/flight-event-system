package ru.protectinfotrans.eca.eventhandling.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.eventhandling.domain.DeliveryStatus;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationChannelSender;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationDeliveryRepositoryPort;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationDispatchService — идемпотентность, фильтр триггера, выбор канала (P3-4)")
class NotificationDispatchServiceTest {

    @Mock
    private EventHandlerResolver resolver;
    @Mock
    private NotificationDeliveryRepositoryPort deliveryRepository;
    @Mock
    private NotificationChannelSender emailSender;

    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        lenient().when(emailSender.supports(NotificationChannelType.EMAIL)).thenReturn(true);
        service = new NotificationDispatchService(resolver, deliveryRepository,
                List.of(emailSender), new SimpleMeterRegistry());
    }

    private StepNotificationEvent event(boolean success) {
        return new StepNotificationEvent(100L, 3, success ? StepResult.SUCCESS : StepResult.FAILURE,
                "VP-BQR", success, 7L, 99L);
    }

    private EventHandler handler(Long id, NotificationTrigger trigger) {
        return EventHandler.builder()
                .id(id).scope(HandlerScope.FOLDER).scopeId(99L)
                .triggerType(trigger).channel(NotificationChannelType.EMAIL)
                .target("ops@example.com").enabled(true).build();
    }

    @Test
    @DisplayName("новая доставка: канал отправляет, строка delivery сохраняется со статусом SENT")
    void deliversAndPersistsSent() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of(handler(1L, NotificationTrigger.ON_ANY)));
        when(deliveryRepository.existsByDedupKey(100L, 3, "SUCCESS", 1L)).thenReturn(false);
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.onStepNotification(event(true));

        verify(emailSender).send(anyString(), anyString(), anyString());
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(captor.getValue().getResult()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("уже доставлено (дедуп) -> канал НЕ дёргается, новой строки нет (идемпотентность)")
    void idempotentSkipWhenAlreadyDelivered() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of(handler(1L, NotificationTrigger.ON_ANY)));
        when(deliveryRepository.existsByDedupKey(100L, 3, "SUCCESS", 1L)).thenReturn(true);

        service.onStepNotification(event(true));

        verify(emailSender, never()).send(any(), any(), any());
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    @DisplayName("триггер ON_SUCCESS не срабатывает на FAILURE")
    void triggerFilteringSkipsMismatch() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of(handler(1L, NotificationTrigger.ON_SUCCESS)));

        service.onStepNotification(event(false));

        verifyNoInteractions(deliveryRepository);
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("канал вернул false -> строка delivery со статусом FAILED")
    void persistsFailedWhenChannelRejects() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of(handler(1L, NotificationTrigger.ON_ANY)));
        when(deliveryRepository.existsByDedupKey(100L, 3, "SUCCESS", 1L)).thenReturn(false);
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(false);

        service.onStepNotification(event(true));

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("несколько обработчиков -> доставка каждому независимо")
    void deliversToMultipleHandlers() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of(
                handler(1L, NotificationTrigger.ON_ANY), handler(2L, NotificationTrigger.ON_ANY)));
        when(deliveryRepository.existsByDedupKey(any(), any(), anyString(), any())).thenReturn(false);
        when(emailSender.send(anyString(), anyString(), anyString())).thenReturn(true);

        service.onStepNotification(event(true));

        verify(emailSender, times(2)).send(anyString(), anyString(), anyString());
        verify(deliveryRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("обработчиков нет -> ничего не делаем")
    void noHandlersNoOp() {
        when(resolver.resolve(7L, 99L)).thenReturn(List.of());

        service.onStepNotification(event(true));

        verifyNoInteractions(deliveryRepository);
        verify(emailSender, never()).send(any(), any(), any());
    }
}
