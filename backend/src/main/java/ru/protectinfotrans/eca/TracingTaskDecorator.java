package ru.protectinfotrans.eca;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.core.task.TaskDecorator;

/**
 * P5-2: {@link TaskDecorator} для проброса Micrometer/OTel trace-контекста через @Async-границу.
 *
 * <p>Spring Modulith использует {@code applicationTaskExecutor} (ThreadPoolTaskExecutor, настраивается
 * через {@code spring.task.execution.*}) для {@code @ApplicationModuleListener}
 * ({@code @Async + @TransactionalEventListener}). При переключении потока OTel-контекст (текущий
 * span, baggage) хранится в thread-local и по умолчанию НЕ наследуется новым потоком — сквозная
 * трассировка «входящее сообщение → движок ECA → исходящее» была бы разорвана на каждой
 * {@code @ApplicationModuleListener}-границе: {@code ExecutionService#processEvent} начинал бы
 * новый trace вместо того, чтобы стать дочерним span'ом HTTP-запроса.
 *
 * <p><b>Паттерн Micrometer context-propagation:</b>
 * <ol>
 *   <li>{@code ContextSnapshotFactory.captureAll()} захватывает снапшот ВСЕХ зарегистрированных
 *       thread-local'ов (OTel {@code Context}, Baggage, Reactor Context и т.п.) в потоке-ПУБЛИКАТОРЕ
 *       события (HTTP-запрос / транзакционный поток).</li>
 *   <li>{@code snapshot.setThreadLocals()} восстанавливает захваченный контекст в потоке-ПОДПИСЧИКЕ
 *       ({@code @ApplicationModuleListener} worker) на время выполнения задачи.</li>
 * </ol>
 *
 * <p>Регистрируется через {@link ObservabilityAsyncConfig#tracingTaskExecutorCustomizer} как
 * {@code ThreadPoolTaskExecutorCustomizer} — Spring Boot 3.x применяет его к
 * {@code applicationTaskExecutor} автоматически через {@code TaskExecutionAutoConfiguration}.
 * В тестах ({@code BaseIntegrationTest.SyncAsyncConfig}) executor заменён на {@code SyncTaskExecutor}:
 * смены потока нет, decorator не применяется — OTel-контекст присутствует в том же потоке.
 *
 * @see ObservabilityAsyncConfig
 * @see io.micrometer.context.ContextSnapshotFactory
 */
public class TracingTaskDecorator implements TaskDecorator {

    private final ContextSnapshotFactory snapshotFactory;

    public TracingTaskDecorator(ContextSnapshotFactory snapshotFactory) {
        this.snapshotFactory = snapshotFactory;
    }

    /**
     * Захватывает текущий контекст в потоке-публикаторе, возвращает Runnable,
     * который перед запуском задачи восстанавливает этот контекст в worker-потоке.
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        // Захват в потоке-публикаторе (HTTP / транзакционный поток)
        ContextSnapshot snapshot = snapshotFactory.captureAll();
        return () -> {
            // Восстановление в worker-потоке (@ApplicationModuleListener executor)
            try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                runnable.run();
            }
        };
    }
}
