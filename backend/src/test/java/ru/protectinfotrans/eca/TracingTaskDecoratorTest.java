package ru.protectinfotrans.eca;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тест {@link TracingTaskDecorator}: доказывает, что OTel trace-контекст
 * пробрасывается через РЕАЛЬНУЮ смену потока.
 *
 * <p><b>Почему оригинальный plain-JUnit тест падал:</b>
 * {@link ContextSnapshotFactory#captureAll()} захватывает только значения зарегистрированных
 * {@link ThreadLocalAccessor}ов из {@link ContextRegistry#getInstance()}. Без
 * зарегистрированного accessor'а для OTel-контекста {@code captureAll()} ничего не захватывал:
 * worker-поток видел пустой {@link Context} → traceId = "000...0" → ассерт падал.
 *
 * <p><b>Исправление — {@link OtelContextAccessor}:</b> явно регистрируем accessor в
 * {@link ContextRegistry#getInstance()} в {@code @BeforeEach} и убираем в {@code @AfterEach}.
 * Он напрямую читает {@link Context#current()} (OTel thread-local: SPI
 * {@code ContextStorageProvider} отсутствует в {@code micrometer-tracing-bridge-otel 1.5.x},
 * поэтому OTel использует собственный {@code ThreadLocalContextStorage}) и восстанавливает
 * его через {@link Context#makeCurrent()}, сохраняя возвращённый {@link Scope} для
 * закрытия в {@link OtelContextAccessor#setValue()}.
 *
 * <p><b>API {@code context-propagation 1.1.4}:</b>
 * {@code DefaultContextSnapshot.setThreadLocals()} вызывает {@link OtelContextAccessor#setValue(Context)}
 * (установить захваченный контекст), а {@code DefaultScope.close()} вызывает
 * {@code restore(previousCtx)} → {@link OtelContextAccessor#setValue(Context)} (восстановить предыдущий)
 * или {@link OtelContextAccessor#setValue()} (сброс без предыдущего значения).
 *
 * <p><b>Что доказывает тест:</b>
 * <ul>
 *   <li>(а) Caller-поток и worker-поток — РАЗНЫЕ потоки (имена различаются).</li>
 *   <li>(б) Worker-поток видит тот же {@code traceId}: OTel-контекст переехал через границу
 *       потоков благодаря {@link TracingTaskDecorator}.</li>
 *   <li>Без {@link OtelContextAccessor} в {@code ContextRegistry} (исходная ошибка)
 *       worker получал "00000000000000000000000000000000".</li>
 * </ul>
 */
@DisplayName("TracingTaskDecorator: проброс OTel-контекста через реальную смену потока")
class TracingTaskDecoratorTest {

    // -------------------------------------------------------------------------
    // OtelContextAccessor — прямой мост OTel thread-local ↔ Micrometer ContextRegistry
    // -------------------------------------------------------------------------

    /**
     * {@link ThreadLocalAccessor} для {@link Context}: напрямую читает/восстанавливает
     * OTel thread-local. Без SPI {@code ContextStorageProvider} (проверено: отсутствует
     * в {@code micrometer-tracing-bridge-otel 1.5.x}) OTel хранит контекст в приватном
     * {@code ThreadLocalContextStorage}; {@link Context#current()} и {@link Context#makeCurrent()}
     * читают/пишут именно его.
     *
     * <p>{@code context-propagation 1.1.4} вызывает методы в следующем порядке:
     * <ol>
     *   <li>{@link #getValue()} в caller-потоке → захватывает текущий контекст.</li>
     *   <li>{@link #setValue(Context)} в worker-потоке → открывает scope, сохраняет в SCOPE.</li>
     *   <li>{@link #setValue(Context)} или {@link #setValue()} при закрытии scope →
     *       закрывает предыдущий scope, восстанавливая OTel thread-local.</li>
     * </ol>
     */
    static final class OtelContextAccessor implements ThreadLocalAccessor<Context> {

        static final String KEY = "test.otel.context";

        /**
         * Scope, открытый последним вызовом {@link #setValue(Context)}.
         * Закрывается в следующем вызове {@link #setValue(Context)} или {@link #setValue()}.
         */
        private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

        @Override
        public Object key() {
            return KEY;
        }

        @Override
        public Context getValue() {
            return Context.current();
        }

        /**
         * Закрывает предыдущий scope (если есть), открывает новый для {@code context}.
         * Вызывается как для установки захваченного контекста в worker-потоке,
         * так и для восстановления предыдущего контекста при закрытии snapshot-scope.
         */
        @Override
        public void setValue(Context context) {
            Scope previous = SCOPE.get();
            if (previous != null) {
                SCOPE.remove();
                previous.close();
            }
            SCOPE.set(context.makeCurrent());
        }

        /**
         * Закрывает scope без открытия нового — сброс к root-контексту.
         * Вызывается когда у accessor'а не было предыдущего значения в snapshot.
         * Переопределяем default-реализацию из {@code context-propagation 1.1.4}:
         * {@code default setValue() → reset() → throws IllegalStateException}.
         */
        @Override
        public void setValue() {
            Scope s = SCOPE.get();
            SCOPE.remove();
            if (s != null) {
                s.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test state
    // -------------------------------------------------------------------------

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        // 1. Регистрируем accessor ДО создания ContextSnapshotFactory,
        //    чтобы captureAll() подхватил его через ContextRegistry.getInstance().
        ContextRegistry.getInstance().registerThreadLocalAccessor(new OtelContextAccessor());

        // 2. Автономный OTel SDK (не глобальный OpenTelemetry.getGlobal()):
        //    span'ы реальные (sampling 100%), хранятся в собственном OTel thread-local.
        spanExporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        // 3. ContextSnapshotFactory создаётся после регистрации accessor'а:
        //    использует ContextRegistry.getInstance() и видит OtelContextAccessor.
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();

        // 4. Executor с декоратором: один worker-поток для гарантированной смены потока.
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("decorator-test-worker-");
        executor.setTaskDecorator(new TracingTaskDecorator(snapshotFactory));
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        openTelemetry.close();
        // Удаляем accessor из глобального реестра, чтобы не влиять на другие тесты.
        ContextRegistry.getInstance().removeThreadLocalAccessor(OtelContextAccessor.KEY);
    }

    // -------------------------------------------------------------------------
    // Test
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("worker-поток видит тот же traceId и является ДРУГИМ потоком относительно вызывающего")
    void tracingContextPropagatedAcrossThreadBoundary() throws Exception {
        // Caller-поток (JUnit test-runner)
        Tracer tracer = openTelemetry.getTracer("decorator-unit-test");
        Span callerSpan = tracer.spanBuilder("caller-root-span").startSpan();
        String callerThreadName = Thread.currentThread().getName();
        CompletableFuture<String[]> workerCapture = new CompletableFuture<>();

        String callerTraceId;
        try (Scope ignored = callerSpan.makeCurrent()) {
            callerTraceId = Span.current().getSpanContext().getTraceId();

            assertThat(callerTraceId)
                    .as("span в вызывающем потоке валиден (не пустой и не нулевой)")
                    .isNotBlank()
                    .isNotEqualTo("00000000000000000000000000000000");

            // TracingTaskDecorator.decorate() вызывается здесь, в caller-потоке:
            // captureAll() → OtelContextAccessor.getValue() = Context.current() (содержит callerSpan)
            // → захвачен контекст с callerSpan.
            executor.execute(() -> {
                // Выполняется в worker-потоке (decorator-test-worker-N).
                // snapshot.setThreadLocals() → OtelContextAccessor.setValue(capturedCtx)
                //   → capturedCtx.makeCurrent() → OTel thread-local = capturedCtx (с callerSpan).
                String workerTraceId = Span.current().getSpanContext().getTraceId();
                String workerThreadName = Thread.currentThread().getName();
                workerCapture.complete(new String[]{workerTraceId, workerThreadName});
            });
        } finally {
            callerSpan.end();
        }

        String[] result = workerCapture.get(5, TimeUnit.SECONDS);
        String workerTraceId    = result[0];
        String workerThreadName = result[1];

        // (а) Реальная смена потока — имена РАЗЛИЧАЮТСЯ
        assertThat(workerThreadName)
                .as("worker-поток ОТЛИЧАЕТСЯ от вызывающего — смена потока произошла")
                .isNotEqualTo(callerThreadName)
                .startsWith("decorator-test-worker-");

        // (б) Декоратор сработал — traceId пробросился через границу потоков.
        // Без OtelContextAccessor в ContextRegistry (исходная ошибка) captureAll()
        // ничего не захватывал → worker видел пустой Context → traceId = "000...0".
        assertThat(workerTraceId)
                .as("worker-поток видит тот же traceId, что вызывающий — "
                        + "OTel-контекст пробросился через TracingTaskDecorator")
                .isNotBlank()
                .isNotEqualTo("00000000000000000000000000000000")
                .isEqualTo(callerTraceId);
    }
}
