package ru.protectinfotrans.eca.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.dto.IncomingMessageRequest;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-2: интеграционный тест сквозной трассировки OTel — набор span'ов и атрибутов корреляции.
 *
 * <p>Проверяет, что по пути HTTP → движок ECA → outbound создаются все доменные span'ы
 * ({@code eca.engine.process}, {@code eca.execution.instance.started},
 * {@code eca.integration.outbound}), они принадлежат одному {@code traceId} и несут
 * атрибуты корреляции (борт / рейс / инстанс выполнения).
 *
 * <p><b>Важное ограничение:</b> {@code BaseIntegrationTest.SyncAsyncConfig} подменяет
 * {@code applicationTaskExecutor} на {@code SyncTaskExecutor} (@Primary). Поэтому
 * {@code @ApplicationModuleListener} выполняется синхронно в том же HTTP-потоке,
 * где OTel-контекст уже активен — смены потока НЕ происходит, а
 * {@link ru.protectinfotrans.eca.TracingTaskDecorator#decorate(Runnable)} не вызывается.
 * Данный тест проверяет корректность состава span'ов и их атрибутов, но НЕ доказывает
 * работу декоратора через async-границу.
 *
 * <p>Реальная смена потока и работа {@link ru.protectinfotrans.eca.TracingTaskDecorator}
 * покрыты отдельным unit-тестом
 * {@link ru.protectinfotrans.eca.TracingTaskDecoratorTest}, который использует
 * настоящий {@code ThreadPoolTaskExecutor} и доказывает совпадение {@code traceId}
 * при различных именах потоков (caller ≠ worker).
 *
 * <p><b>Схема сценария:</b>
 * <ol>
 *   <li>Создать активную последовательность с start-критерием MESSAGE_RECEIVED(DOWNLINK/DEPART)
 *       и единственным ACTION-шагом SEND_UPLINK → END.</li>
 *   <li>Отправить POST /api/v1/messages/incoming (DOWNLINK/DEPART, борт VP-BQR, рейс SU1234).</li>
 *   <li>В ходе синхронной обработки создаются доменные span'ы:
 *       <ul>
 *         <li>{@code eca.engine.process} — движок ECA (ExecutionService#processEvent), атрибуты
 *             {@code aircraft.id}, {@code flight.id};</li>
 *         <li>{@code eca.execution.instance.started} — старт инстанса (ExecutionService#startExecution),
 *             атрибут {@code execution.instance.id};</li>
 *         <li>{@code eca.integration.outbound} — постановка uplink в durable-очередь
 *             (OutboundMessageGatewayAdapter#sendUplink), атрибуты {@code aircraft.id},
 *             {@code outbound.type}.</li>
 *       </ul>
 *   </li>
 *   <li>Все span'ы принадлежат ОДНОМУ traceId (единый HTTP-поток, SyncTaskExecutor).</li>
 * </ol>
 */
@Import(InMemoryTracingTestConfig.class)
// @AutoConfigureObservability: без этой аннотации @SpringBootTest заменяет ObservationRegistry
// на ObservationRegistry.NOOP (ObservabilityTestAutoConfiguration) — span'ы не создавались бы.
@AutoConfigureObservability
@DisplayName("P5-2: сквозная трассировка OTel (inbound HTTP → движок ECA → outbound)")
class P5_2_TracingScenarioIntTest extends BaseIntegrationTest {

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";
    private static final String TRIGGER_TEMPLATE = "DEPART";
    private static final String UPLINK_TEMPLATE  = "DEP_CLEAR";

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    /**
     * Сброс накопленных span'ов перед каждым тестом (включая все span'ы, возникшие при Flyway
     * clean+migrate в BaseIntegrationTest#resetDatabase и при прогреве Spring-контекста).
     * Гарантирует, что в проверках присутствуют ТОЛЬКО span'ы от тестового сценария.
     */
    @BeforeEach
    void clearCollectedSpans() {
        spanExporter.reset();
    }

    @Test
    @DisplayName("span'ы inbound/engine/outbound одного traceId + атрибуты борт/рейс/инстанс")
    void tracingEndToEndCorrelation() {

        // --- arrange: активная последовательность с ACTION SEND_UPLINK ---
        SequenceResponse seq = sequenceUseCase.createSequence(new SequenceCreateRequest(
                "P5-2 Tracing Test Sequence",
                "Тест сквозной трассировки P5-2",
                // start-критерий: получено DOWNLINK-сообщение с шаблоном DEPART
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"" + TRIGGER_TEMPLATE + "\"}",
                null // stop-критерий отсутствует
        ), 1L);

        // Единственный шаг: ACTION → SEND_UPLINK → END (success) / END (failure)
        sequenceUseCase.addStep(seq.id(), new StepCreateRequest(
                "Send Departure Clearance",
                StepType.ACTION,
                "{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"" + UPLINK_TEMPLATE + "\","
                        + "\"origin\":\"COMPUTER_GENERATED\"}",
                null,
                TransitionAction.END, null, false,
                TransitionAction.END, null, false
        ), 1L);

        sequenceUseCase.activateSequence(seq.id(), 1L);

        // Сбрасываем span'ы, накопленные в ходе setup (create/addStep/activate — rest-вызовы
        // через sequenceUseCase генерируют HTTP-span'ы, не относящиеся к тесту).
        spanExporter.reset();

        // --- act: входящее сообщение → движок ECA (processEvent, SyncTaskExecutor) → outbound ---
        IncomingMessageRequest msgRequest = new IncomingMessageRequest(
                MessageType.DOWNLINK,
                TRIGGER_TEMPLATE,
                AIRCRAFT_ID,
                FLIGHT_NUMBER,
                null,
                null
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/messages/incoming",
                new HttpEntity<>(msgRequest, headers),
                String.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // --- assert ---
        // С @ApplicationModuleListener (async-диспетчеризация в task-1) span eca.engine.process
        // завершается в фоновом потоке ПОСЛЕ того, как HTTP-ответ уже отправлен. Awaitility ждёт
        // появления всех трёх eca.*-span'ов в экспортёре (≤5 с, интервал 100 мс), затем
        // читает единый снимок для всех последующих проверок.
        Set<String> expectedEcaSpanNames = Set.of(
                "eca.engine.process",
                "eca.execution.instance.started",
                "eca.integration.outbound"
        );
        Awaitility.await("все eca.*-span'ы экспортированы в InMemorySpanExporter")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    Set<String> found = spanExporter.getFinishedSpanItems().stream()
                            .map(SpanData::getName)
                            .filter(n -> n.startsWith("eca."))
                            .collect(Collectors.toSet());
                    return found.containsAll(expectedEcaSpanNames);
                });

        List<SpanData> allSpans = spanExporter.getFinishedSpanItems();

        // (а) Доменные span'ы созданы на каждом этапе сквозного пути
        List<SpanData> ecaSpans = allSpans.stream()
                .filter(s -> s.getName().startsWith("eca."))
                .toList();

        List<String> ecaSpanNames = ecaSpans.stream().map(SpanData::getName).toList();
        assertThat(ecaSpanNames)
                .as("ожидаем span eca.engine.process (движок ECA)")
                .contains("eca.engine.process");
        assertThat(ecaSpanNames)
                .as("ожидаем span eca.execution.instance.started (старт инстанса)")
                .contains("eca.execution.instance.started");
        assertThat(ecaSpanNames)
                .as("ожидаем span eca.integration.outbound (постановка uplink в очередь)")
                .contains("eca.integration.outbound");

        // (б) Все eca.*-span'ы принадлежат ОДНОМУ traceId —
        // доказательство сквозной корреляции (контекст пережил границу @ApplicationModuleListener)
        SpanData engineSpan = ecaSpans.stream()
                .filter(s -> "eca.engine.process".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span eca.engine.process не найден"));

        String traceId = engineSpan.getTraceId();
        assertThat(traceId).as("traceId движка не пустой").isNotBlank();

        assertThat(ecaSpans)
                .as("все eca.*-span'ы имеют единый traceId (context пережил @Async-границу)")
                .allSatisfy(span ->
                        assertThat(span.getTraceId())
                                .as("span '%s' должен иметь traceId=%s", span.getName(), traceId)
                                .isEqualTo(traceId));

        // (в) Span'ы несут атрибуты корреляции: борт, рейс, инстанс

        // eca.engine.process: aircraft.id, flight.id
        assertThat(engineSpan.getAttributes().get(AttributeKey.stringKey("aircraft.id")))
                .as("aircraft.id в span движка")
                .isEqualTo(AIRCRAFT_ID);
        assertThat(engineSpan.getAttributes().get(AttributeKey.stringKey("flight.id")))
                .as("flight.id в span движка")
                .isEqualTo(FLIGHT_NUMBER);

        // eca.execution.instance.started: execution.instance.id (непустой Long)
        SpanData instanceSpan = ecaSpans.stream()
                .filter(s -> "eca.execution.instance.started".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span eca.execution.instance.started не найден"));

        assertThat(instanceSpan.getAttributes().get(AttributeKey.stringKey("execution.instance.id")))
                .as("execution.instance.id в span инстанса")
                .isNotBlank();
        assertThat(instanceSpan.getAttributes().get(AttributeKey.stringKey("aircraft.id")))
                .as("aircraft.id в span инстанса")
                .isEqualTo(AIRCRAFT_ID);

        // eca.integration.outbound: aircraft.id, outbound.type
        SpanData outboundSpan = ecaSpans.stream()
                .filter(s -> "eca.integration.outbound".equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span eca.integration.outbound не найден"));

        assertThat(outboundSpan.getAttributes().get(AttributeKey.stringKey("aircraft.id")))
                .as("aircraft.id в span исходящего сообщения")
                .isEqualTo(AIRCRAFT_ID);
        assertThat(outboundSpan.getAttributes().get(AttributeKey.stringKey("outbound.type")))
                .as("outbound.type в span исходящего сообщения")
                .isEqualTo("UPLINK");
    }
}
