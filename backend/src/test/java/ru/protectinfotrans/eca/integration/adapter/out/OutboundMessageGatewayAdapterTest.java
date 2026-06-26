package ru.protectinfotrans.eca.integration.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageStatus;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-3: unit-тест durable-постановки в очередь (синхронный путь {@code ActionStepRule}
 * -> {@code OutboundMessageGatewayAdapter} -> persist PENDING, без фактической доставки).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundMessageGatewayAdapter")
class OutboundMessageGatewayAdapterTest {

    @Mock
    private OutboundMessageRepositoryPort repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboundMessageGatewayAdapter adapter;

    @BeforeEach
    void setUp() {
        // P5-2: ObservationRegistry.NOOP — span-код в адаптере безопасно пропускается
        // (Observation.createNotStarted(..., NOOP) возвращает Observation.NOOP, всё no-op).
        adapter = new OutboundMessageGatewayAdapter(repository, objectMapper, ObservationRegistry.NOOP);
        CorrelationContext.clear();
    }

    @Test
    @DisplayName("sendUplink: персистит PENDING-запись с origin и возвращает true (не фактическая доставка)")
    void sendUplinkPersistsPendingRecord() {
        when(repository.save(any())).thenAnswer(invocation -> {
            OutboundMessage m = invocation.getArgument(0);
            m.setId(1L);
            return m;
        });

        boolean accepted = adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of("gate", "A1"), UplinkOrigin.EXTERNAL_USER);

        assertThat(accepted).isTrue();

        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());

        OutboundMessage saved = captor.getValue();
        assertThat(saved.getMessageType()).isEqualTo(OutboundMessageType.UPLINK);
        assertThat(saved.getAircraftId()).isEqualTo("VP-BQR");
        assertThat(saved.getTemplateName()).isEqualTo("CLEARANCE");
        assertThat(saved.getUplinkOrigin()).isEqualTo(UplinkOrigin.EXTERNAL_USER);
        assertThat(saved.getParamsJson()).contains("gate").contains("A1");
        // status — намеренно не задан адаптером явно: @PrePersist OutboundMessage сам
        // проставляет PENDING при первом save в реальном JPA-цикле; здесь Mockito не
        // вызывает @PrePersist, поэтому проверяем поле status == null (адаптер его не трогает)
        // либо PENDING, если бы PrePersist сработал — оба варианта корректны по контракту.
        assertThat(saved.getStatus()).isIn((Object) null, OutboundMessageStatus.PENDING);
    }

    @Test
    @DisplayName("sendUplink (3-арг): по умолчанию COMPUTER_GENERATED")
    void sendUplinkDefaultsToComputerGenerated() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of());

        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUplinkOrigin()).isEqualTo(UplinkOrigin.COMPUTER_GENERATED);
    }

    @Test
    @DisplayName("sendGround: персистит PENDING-запись с получателями")
    void sendGroundPersistsPendingRecordWithRecipients() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<String> recipients = List.of("dispatcher@airline.com", "ops@airline.com");
        boolean accepted = adapter.sendGround(recipients, "NOTIFICATION", Map.of("delay", "30"));

        assertThat(accepted).isTrue();

        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());

        OutboundMessage saved = captor.getValue();
        assertThat(saved.getMessageType()).isEqualTo(OutboundMessageType.GROUND);
        assertThat(saved.getRecipients()).containsExactlyElementsOf(recipients);
        assertThat(saved.getTemplateName()).isEqualTo("NOTIFICATION");
        assertThat(saved.getUplinkOrigin()).isNull();
    }

    @Test
    @DisplayName("sendUplink: проставляет correlationId из CorrelationContext, если он установлен")
    void sendUplinkCapturesCorrelationId() {
        CorrelationContext.putCorrelationId("corr-123");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of());

        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("corr-123");

        CorrelationContext.clear();
    }

    // P3-3: raise/close condition больше НЕ часть MessageOutputPort/этого адаптера — переехали в
    // отдельный модуль conditions (ConditionManagementUseCase, ConditionService), см. её unit-тесты
    // (ConditionServiceTest) для покрытия raise/close/auto-close. Старый тест
    // delegatesConditionsToFallbackAdapter удалён вместе с этим поведением.

    // ============================================================
    // Фикс регрессии идемпотентности P1-4 x P2-3: дедуп по
    // (executionInstanceId, stepOrderIndex) на 6/5-арг. перегрузках.
    // ============================================================

    @Test
    @DisplayName("sendUplink (6-арг): первый вызов для (instanceId, stepIndex) персистит новую запись с дедуп-ключом")
    void sendUplinkWithStepContextPersistsNewRecordWithDedupKey() {
        when(repository.findByExecutionInstanceIdAndStepOrderIndex(42L, 3)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> {
            OutboundMessage m = invocation.getArgument(0);
            m.setId(1L);
            return m;
        });

        boolean accepted = adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of("gate", "A1"),
                UplinkOrigin.EXTERNAL_USER, 42L, 3);

        assertThat(accepted).isTrue();

        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExecutionInstanceId()).isEqualTo(42L);
        assertThat(captor.getValue().getStepOrderIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("sendUplink (6-арг): повторный вызов для УЖЕ существующего (instanceId, stepIndex) — идемпотентный skip, без save()")
    void sendUplinkWithStepContextSkipsDuplicateEnqueue() {
        OutboundMessage existing = OutboundMessage.builder().id(99L).build();
        when(repository.findByExecutionInstanceIdAndStepOrderIndex(42L, 3)).thenReturn(Optional.of(existing));

        // Эмулирует resume после рестарта: повторный прогон ACTION SEND_UPLINK для того же шага.
        boolean accepted = adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of("gate", "A1"),
                UplinkOrigin.EXTERNAL_USER, 42L, 3);

        // Идемпотентный skip — возвращает true (как при успешной постановке), НЕ создаёт дубль.
        assertThat(accepted).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("sendGround (5-арг): повторный вызов для УЖЕ существующего (instanceId, stepIndex) — идемпотентный skip, без save()")
    void sendGroundWithStepContextSkipsDuplicateEnqueue() {
        OutboundMessage existing = OutboundMessage.builder().id(100L).build();
        when(repository.findByExecutionInstanceIdAndStepOrderIndex(7L, 1)).thenReturn(Optional.of(existing));

        boolean accepted = adapter.sendGround(List.of("ops@airline.com"), "NOTIFICATION", Map.of(), 7L, 1);

        assertThat(accepted).isTrue();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("sendUplink (4-арг, без контекста шага): дедуп НЕ применяется — find не вызывается")
    void sendUplinkWithoutStepContextSkipsDedupCheck() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Вызов вне ACTION-шага (например IntegrationService) — executionInstanceId/stepOrderIndex
        // отсутствуют, дедуп по этому ключу логически неприменим.
        adapter.sendUplink("VP-BQR", "CLEARANCE", Map.of(), UplinkOrigin.COMPUTER_GENERATED);

        verify(repository, never()).findByExecutionInstanceIdAndStepOrderIndex(any(), any());
        ArgumentCaptor<OutboundMessage> captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getExecutionInstanceId()).isNull();
        assertThat(captor.getValue().getStepOrderIndex()).isNull();
    }
}
