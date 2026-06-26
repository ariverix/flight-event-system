package ru.protectinfotrans.eca.observability;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * P5-2: тестовая конфигурация для перехвата OTel span'ов в памяти (без реального OTLP-коллектора).
 *
 * <p>Регистрирует {@link InMemorySpanExporter} как {@link SpanProcessor} (через
 * {@link SimpleSpanProcessor}) в {@code SdkTracerProvider}. Spring Boot 3.x
 * {@code OpenTelemetryAutoConfiguration} подхватывает {@code SpanProcessor}-бины через
 * {@code ObjectProvider<SpanProcessor>} при построении {@code SdkTracerProvider}:
 * <pre>
 *   additionalSpanProcessors.orderedStream().forEach(builder::addSpanProcessor);
 * </pre>
 *
 * <p>{@link SimpleSpanProcessor} — синхронный: span экспортируется сразу при закрытии, без
 * batch-буфера. Это критично для тестов: к моменту завершения HTTP-вызова все span'ы уже
 * доступны через {@link InMemorySpanExporter#getFinishedSpanItems()} — без Awaitility/sleep.
 *
 * <p>Использование в тесте:
 * <pre>
 *   &#64;Import(InMemoryTracingTestConfig.class)
 *   &#64;AutoConfigureObservability  // включает реальный ObservationRegistry (не NOOP)
 *   class P5_2_TracingScenarioIntTest extends BaseIntegrationTest { ... }
 * </pre>
 *
 * @see P5_2_TracingScenarioIntTest
 */
@TestConfiguration
public class InMemoryTracingTestConfig {

    /**
     * InMemorySpanExporter из {@code opentelemetry-sdk-testing} (test scope в pom.xml).
     * Доступен через {@code @Autowired InMemorySpanExporter spanExporter} в тестовом классе.
     */
    @Bean
    public InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    /**
     * SimpleSpanProcessor оборачивает InMemorySpanExporter и регистрируется как SpanProcessor-бин.
     * Spring Boot OTel auto-configuration добавляет его напрямую в SdkTracerProvider.
     */
    @Bean
    public SpanProcessor inMemorySimpleSpanProcessor(InMemorySpanExporter exporter) {
        return SimpleSpanProcessor.create(exporter);
    }
}
