package ru.protectinfotrans.eca.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;
import ru.protectinfotrans.eca.templates.port.in.TemplateRenderUseCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-3/P2-6: unit-тесты поллера durable-доставки + circuit breaker/backoff. Реальный single-fire
 * под конкуренцией проверяется интеграционным тестом на Postgres
 * ({@code P2_3_OutboundGatewayScenarioIntTest}/{@code P2_6_DlqAndResilienceScenarioIntTest}) —
 * здесь проверяется логика {@code deliverOne}/{@code pollPendingMessages} относительно мокнутых
 * портов (claim-результат, переход статусов, circuit breaker решения, счётчики).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboundMessageDeliveryScheduler")
class OutboundMessageDeliverySchedulerTest {

    @Mock
    private OutboundMessageRepositoryPort repository;

    @Mock
    private CircuitBreakerRepositoryPort circuitBreakerRepository;

    // P2-3: self-инъекция через ObjectProvider (см. javadoc класса) — в unit-тесте нет реального
    // Spring AOP-прокси, поэтому self.getObject() стабится так, чтобы возвращать ТОТ ЖЕ объект,
    // который тестируем. @Transactional(REQUIRES_NEW) семантика проверяется интеграционным
    // тестом single-fire на реальном Postgres (P2_3_OutboundGatewayScenarioIntTest).
    @Mock
    private ObjectProvider<OutboundMessageDeliveryScheduler> self;

    // P3-1: реестр шаблонов — никакой из тестовых templateName ("CLEARANCE", "T1", "T2") не
    // зарегистрирован, поэтому tryRender всегда Optional.empty() — это значит OutboundMessageDeliveryScheduler
    // отправляет templateName как есть (обратная совместимость, см. javadoc renderTemplate).
    @Mock
    private TemplateRenderUseCase templateRenderUseCase;

    private OutboundMessageDeliveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        // P6-1: pollPendingMessages() негейтуем (его и тестируем напрямую); лидер-заглушка нужна
        // только для конструктора — для прямого вызова pollPendingMessages значение isLeader не важно.
        scheduler = new OutboundMessageDeliveryScheduler(repository, circuitBreakerRepository, self,
                () -> true, new ObjectMapper(), templateRenderUseCase, new SimpleMeterRegistry());
        org.mockito.Mockito.lenient().when(self.getObject()).thenReturn(scheduler);
        org.mockito.Mockito.lenient().when(circuitBreakerRepository.getOrCreate(any()))
                .thenReturn(closedBreaker(OutboundMessageType.UPLINK));
        org.mockito.Mockito.lenient().when(templateRenderUseCase.tryRender(anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private static ChannelCircuitBreaker closedBreaker(OutboundMessageType channel) {
        return ChannelCircuitBreaker.builder()
                .channel(channel)
                .state(CircuitBreakerState.CLOSED)
                .consecutiveFailures(0)
                .build();
    }

    @Nested
    @DisplayName("deliverOne — happy path / claim contention")
    class DeliverOneBasics {

        @Test
        @DisplayName("claim успешен, breaker CLOSED -> доставляет и помечает SENT + breaker recordSuccess")
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
            verify(circuitBreakerRepository).recordSuccess(OutboundMessageType.UPLINK);
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("claim не удался (другой поллер забрал) -> ничего не делает, breaker не трогается")
        void deliverOneNoOpWhenClaimFails() {
            when(repository.claimPending(1L)).thenReturn(false);

            scheduler.deliverOne(1L);

            verify(repository, never()).findById(anyLong());
            verify(repository, never()).markSent(anyLong(), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
            verify(circuitBreakerRepository, never()).getOrCreate(any());
        }

        @Test
        @DisplayName("запись пропала между claim и findById -> ничего не делает (без NPE)")
        void deliverOneNoOpWhenMessageDisappeared() {
            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.empty());

            scheduler.deliverOne(1L);

            verify(repository, never()).markSent(anyLong(), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
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
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.GROUND))
                    .thenReturn(closedBreaker(OutboundMessageType.GROUND));

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

    @Nested
    @DisplayName("P2-6: backoff при сбое доставки")
    class BackoffOnFailure {

        @Test
        @DisplayName("успешная доставка -> breaker НЕ трогается через markFailed/recordFailure")
        void successfulDeliveryNeverTouchesFailurePath() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.GROUND)
                    .recipients(List.of("ops@airline.com"))
                    .templateName("CLEARANCE")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.GROUND))
                    .thenReturn(closedBreaker(OutboundMessageType.GROUND));

            scheduler.deliverOne(1L);

            verify(repository).markSent(eq(1L), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
            verify(circuitBreakerRepository, never()).recordFailure(any(), anyBoolean(), anyInt(), any());
        }

        @Test
        @DisplayName("сбой канала (params.__simulateFailure) -> markFailed с nextAttemptTime в будущем (экспоненциальный backoff) + circuitBreakerRepository.recordFailure")
        void deliveryFailureSchedulesNextAttemptInFutureAndRecordsBreakerFailure() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .paramsJson("{\"__simulateFailure\":true}")
                    .attempts(1) // attemptsSoFar=1 -> delay = min(5s * 2^1, 5min) = 10s
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK))
                    .thenReturn(closedBreaker(OutboundMessageType.UPLINK));

            LocalDateTime before = LocalDateTime.now();
            scheduler.deliverOne(1L);

            verify(repository, never()).markSent(anyLong(), any());

            ArgumentCaptor<LocalDateTime> nextAttemptCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).markFailed(eq(1L), anyString(), eq(5), nextAttemptCaptor.capture());
            // delayFor(1) = 10s — не "прямо сейчас", строго в будущем относительно момента сбоя
            assertThat(nextAttemptCaptor.getValue()).isAfter(before.plusSeconds(9));

            ArgumentCaptor<Integer> failuresCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(circuitBreakerRepository).recordFailure(eq(OutboundMessageType.UPLINK), eq(false),
                    failuresCaptor.capture(), any());
            assertThat(failuresCaptor.getValue()).isEqualTo(1); // 0 (CLOSED, consecutiveFailures=0) + 1
        }

        @Test
        @DisplayName("серия сбоев достигает порога -> recordFailure(shouldOpen=true) открывает breaker")
        void repeatedFailuresOpenCircuitBreakerAtThreshold() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .paramsJson("{\"__simulateFailure\":true}")
                    .attempts(4)
                    .build();

            ChannelCircuitBreaker almostOpen = ChannelCircuitBreaker.builder()
                    .channel(OutboundMessageType.UPLINK)
                    .state(CircuitBreakerState.CLOSED)
                    .consecutiveFailures(4) // DEFAULT_FAILURE_THRESHOLD=5 — следующий сбой откроет breaker
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK)).thenReturn(almostOpen);

            scheduler.deliverOne(1L);

            verify(circuitBreakerRepository).recordFailure(eq(OutboundMessageType.UPLINK), eq(true), eq(5), any());
        }
    }

    @Nested
    @DisplayName("P2-6: circuit breaker — fail-fast в OPEN")
    class CircuitBreakerBlocking {

        @Test
        @DisplayName("breaker OPEN, таймаут не истёк -> BLOCK: claim освобождён без инкремента attempts, канал не трогается")
        void blockedWhenBreakerOpenAndTimeoutNotExpired() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .attempts(2)
                    .build();

            ChannelCircuitBreaker openBreaker = ChannelCircuitBreaker.builder()
                    .channel(OutboundMessageType.UPLINK)
                    .state(CircuitBreakerState.OPEN)
                    .consecutiveFailures(5)
                    .openedAt(LocalDateTime.now()) // только что открылся — таймаут точно не истёк
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK)).thenReturn(openBreaker);

            scheduler.deliverOne(1L);

            // fail-fast: НЕ markSent, НЕ markFailed (это решение breaker'а, не сбой ЭТОГО сообщения)
            verify(repository, never()).markSent(anyLong(), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
            // claim возвращается в PENDING через releaseClaim — отдельный путь от markFailed
            ArgumentCaptor<LocalDateTime> nextAttemptCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(repository).releaseClaim(eq(1L), nextAttemptCaptor.capture());
            assertThat(nextAttemptCaptor.getValue()).isAfter(LocalDateTime.now());
            verify(circuitBreakerRepository, never()).claimHalfOpenProbe(any());
        }

        @Test
        @DisplayName("breaker OPEN, таймаут истёк, claimHalfOpenProbe выигран -> пробная доставка происходит")
        void probeAllowedWhenTimeoutExpiredAndProbeClaimed() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .attempts(5)
                    .build();

            ChannelCircuitBreaker openBreakerExpired = ChannelCircuitBreaker.builder()
                    .channel(OutboundMessageType.UPLINK)
                    .state(CircuitBreakerState.OPEN)
                    .consecutiveFailures(5)
                    .openedAt(LocalDateTime.now().minusMinutes(10)) // намного больше таймаута восстановления (30с)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK)).thenReturn(openBreakerExpired);
            when(circuitBreakerRepository.claimHalfOpenProbe(OutboundMessageType.UPLINK)).thenReturn(true);

            scheduler.deliverOne(1L);

            // пробная попытка прошла как обычная доставка -> успех -> SENT + breaker recordSuccess
            verify(repository).markSent(eq(1L), any());
            verify(circuitBreakerRepository).recordSuccess(OutboundMessageType.UPLINK);
            verify(repository, never()).releaseClaim(anyLong(), any());
        }

        @Test
        @DisplayName("breaker OPEN, таймаут истёк, но claimHalfOpenProbe ПРОИГРАН (другой кандидат уже забрал пробу) -> releaseClaim без последствий")
        void releasedWhenProbeAlreadyClaimedByAnotherCandidate() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(2L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .attempts(5)
                    .build();

            ChannelCircuitBreaker openBreakerExpired = ChannelCircuitBreaker.builder()
                    .channel(OutboundMessageType.UPLINK)
                    .state(CircuitBreakerState.OPEN)
                    .consecutiveFailures(5)
                    .openedAt(LocalDateTime.now().minusMinutes(10))
                    .build();

            when(repository.claimPending(2L)).thenReturn(true);
            when(repository.findById(2L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK)).thenReturn(openBreakerExpired);
            when(circuitBreakerRepository.claimHalfOpenProbe(OutboundMessageType.UPLINK)).thenReturn(false);

            scheduler.deliverOne(2L);

            verify(repository, never()).markSent(anyLong(), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
            verify(repository).releaseClaim(eq(2L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("breaker CLOSED -> claimHalfOpenProbe никогда не вызывается")
        void halfOpenProbeNeverCalledWhenBreakerClosed() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK))
                    .thenReturn(closedBreaker(OutboundMessageType.UPLINK));

            scheduler.deliverOne(1L);

            verify(circuitBreakerRepository, never()).claimHalfOpenProbe(any());
            verify(repository).markSent(eq(1L), any());
        }
    }

    @Nested
    @DisplayName("P3-1: рендеринг шаблона перед фактической отправкой")
    class TemplateRenderingOnDelivery {

        @Test
        @DisplayName("шаблон зарегистрирован -> tryRender вызывается с десериализованными params, доставка успешна")
        void rendersRegisteredTemplateWithDeserializedParams() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("REQUEST_POSITION")
                    .paramsJson("{\"eta\":\"12:30\"}")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(templateRenderUseCase.tryRender(eq("REQUEST_POSITION"), any()))
                    .thenReturn(Optional.of("Please report position, ETA 12:30"));

            scheduler.deliverOne(1L);

            verify(templateRenderUseCase).tryRender(eq("REQUEST_POSITION"),
                    eq(java.util.Map.of("eta", "12:30")));
            verify(repository).markSent(eq(1L), any());
        }

        @Test
        @DisplayName("шаблон НЕ зарегистрирован (обратная совместимость) -> доставка всё равно успешна")
        void fallsBackToTemplateNameWhenNotRegistered() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.GROUND)
                    .recipients(List.of("ops@airline.com"))
                    .templateName("LEGACY_FREE_TEXT")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            // templateRenderUseCase.tryRender по умолчанию (setUp) -> Optional.empty()

            scheduler.deliverOne(1L);

            verify(repository).markSent(eq(1L), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("paramsJson отсутствует/пуст -> tryRender вызывается с пустой картой переменных")
        void rendersWithEmptyVariablesWhenParamsJsonMissing() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("FREE_TEXT")
                    .paramsJson(null)
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(templateRenderUseCase.tryRender(eq("FREE_TEXT"), eq(java.util.Map.of())))
                    .thenReturn(Optional.of("Hello"));

            scheduler.deliverOne(1L);

            verify(templateRenderUseCase).tryRender(eq("FREE_TEXT"), eq(java.util.Map.of()));
            verify(repository).markSent(eq(1L), any());
        }

        @Test
        @DisplayName("tryRender бросает наружу (например, UnexpectedRollbackException его собственной "
                + "REQUIRES_NEW-транзакции) -> доставка изолирована, всё равно успешна с fallback на имя")
        void deliverySurvivesTryRenderThrowing() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("CLEARANCE")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(templateRenderUseCase.tryRender(eq("CLEARANCE"), any()))
                    .thenThrow(new org.springframework.transaction.UnexpectedRollbackException(
                            "Transaction silently rolled back because it has been marked as rollback-only"));

            scheduler.deliverOne(1L);

            verify(repository).markSent(eq(1L), any());
            verify(repository, never()).markFailed(anyLong(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("шаблон ЗАРЕГИСТРИРОВАН, но не хватает переменной -> НЕ markSent с именем "
                + "шаблона как мусорным текстом; markFailed (сбой доставки, backoff/retry), "
                + "счётчик инкрементнут, breaker.recordFailure вызван")
        void registeredTemplateMissingVariableMarksFailedInsteadOfSendingGarbage() {
            OutboundMessage message = OutboundMessage.builder()
                    .id(1L)
                    .messageType(OutboundMessageType.UPLINK)
                    .aircraftId("VP-BQR")
                    .templateName("REQUEST_POSITION")
                    .paramsJson("{}")
                    .attempts(0)
                    .build();

            when(repository.claimPending(1L)).thenReturn(true);
            when(repository.findById(1L)).thenReturn(Optional.of(message));
            when(templateRenderUseCase.tryRender(eq("REQUEST_POSITION"), any()))
                    .thenThrow(new MissingTemplateVariableException("eta"));

            scheduler.deliverOne(1L);

            // главное: НИКОГДА markSent — ни с отрендеренным текстом, ни (что было дефектом)
            // с именем шаблона "REQUEST_POSITION" в качестве мусорного текста
            verify(repository, never()).markSent(anyLong(), any());

            // вместо этого — обычный путь сбоя доставки: markFailed (-> backoff/retry,
            // в итоге FAILED после MAX_ATTEMPTS по существующей механике P2-6)
            ArgumentCaptor<String> lastErrorCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).markFailed(eq(1L), lastErrorCaptor.capture(), eq(5), any(LocalDateTime.class));
            assertThat(lastErrorCaptor.getValue()).contains("eta");

            // breaker считает это обычным сбоем канала (та же механика P2-6, что и для любого
            // другого сбоя simulateChannelSend)
            verify(circuitBreakerRepository).recordFailure(eq(OutboundMessageType.UPLINK), eq(false),
                    eq(1), any());
        }
    }
}
