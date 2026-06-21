package ru.protectinfotrans.eca.integration.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-3: unit-тесты поллера durable-доставки. Реальный single-fire под конкуренцией
 * проверяется интеграционным тестом на Postgres ({@code OutboundMessageDeliveryIT}) —
 * здесь проверяется только логика самого {@code deliverOne}/{@code pollPendingMessages}
 * относительно мокнутого порта (claim-результат, переход статусов, счётчики).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundMessageDeliveryScheduler")
class OutboundMessageDeliverySchedulerTest {

    @Mock
    private OutboundMessageRepositoryPort repository;

    // P2-3: self-инъекция через ObjectProvider (см. javadoc класса) — в unit-тесте нет реального
    // Spring AOP-прокси, поэтому self.getObject() стабится так, чтобы возвращать ТОТ ЖЕ объект,
    // который тестируем. @Transactional(REQUIRES_NEW) семантика проверяется интеграционным
    // тестом single-fire на реальном Postgres (P2_3_OutboundGatewayScenarioIntTest).
    @Mock
    private ObjectProvider<OutboundMessageDeliveryScheduler> self;

    private OutboundMessageDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OutboundMessageDeliveryScheduler(repository, self, new SimpleMeterRegistry());
        org.mockito.Mockito.lenient().when(self.getObject()).thenReturn(scheduler);
    }

    @Test
    @DisplayName("deliverOne: claim успешен -> доставляет и помечает SENT")
    void deliverOneMarksSentWhenClaimSucceeds() {
        OutboundMessage message = OutboundMessage.builder()
                .id(1L)
                .messageType(OutboundMessageType.UPLINK)
                .aircraftId("VP-BQR")
                .templateName("CLEARANCE")
                .attempts(0)
                .build();

        when(repository.claimPending(1L)).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(message));

        scheduler.deliverOne(1L);

        verify(repository).markSent(eq(1L), any(LocalDateTime.class));
        verify(repository, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("deliverOne: claim не удался (другой поллер забрал) -> ничего не делает")
    void deliverOneNoOpWhenClaimFails() {
        when(repository.claimPending(1L)).thenReturn(false);

        scheduler.deliverOne(1L);

        verify(repository, never()).findById(anyLong());
        verify(repository, never()).markSent(anyLong(), any());
        verify(repository, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("deliverOne: запись пропала между claim и findById -> ничего не делает (без NPE)")
    void deliverOneNoOpWhenMessageDisappeared() {
        when(repository.claimPending(1L)).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.empty());

        scheduler.deliverOne(1L);

        verify(repository, never()).markSent(anyLong(), any());
        verify(repository, never()).markFailed(anyLong(), anyString(), anyInt());
    }

    @Test
    @DisplayName("pollPendingMessages: для каждого кандидата вызывает claim+доставку")
    void pollPendingMessagesProcessesAllCandidates() {
        OutboundMessage m1 = OutboundMessage.builder().id(1L).messageType(OutboundMessageType.UPLINK)
                .aircraftId("VP-AAA").templateName("T1").attempts(0).build();
        OutboundMessage m2 = OutboundMessage.builder().id(2L).messageType(OutboundMessageType.GROUND)
                .recipients(List.of("ops@airline.com")).templateName("T2").attempts(0).build();

        when(repository.findPendingCandidates(any(LocalDateTime.class), eq(50))).thenReturn(List.of(m1, m2));
        when(repository.claimPending(1L)).thenReturn(true);
        when(repository.claimPending(2L)).thenReturn(true);
        when(repository.findById(1L)).thenReturn(Optional.of(m1));
        when(repository.findById(2L)).thenReturn(Optional.of(m2));

        scheduler.pollPendingMessages();

        verify(repository, times(1)).markSent(eq(1L), any());
        verify(repository, times(1)).markSent(eq(2L), any());
    }

    @Test
    @DisplayName("pollPendingMessages: сбойный тик не выбрасывает исключение наружу")
    void pollPendingMessagesSwallowsExceptions() {
        when(repository.findPendingCandidates(any(LocalDateTime.class), eq(50)))
                .thenThrow(new RuntimeException("DB unavailable"));

        scheduler.pollPendingMessages();
        // никакого исключения наружу — поведение @Scheduled не должно умирать навсегда
    }
}
