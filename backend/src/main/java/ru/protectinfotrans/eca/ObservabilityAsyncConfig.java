package ru.protectinfotrans.eca;

import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * P5-2: конфигурация async-исполнителя с проброcом trace-контекста через @Async-границу.
 *
 * <p>Spring Modulith использует {@code applicationTaskExecutor} (ThreadPoolTaskExecutor,
 * настроенный Spring Boot через {@code spring.task.execution.*}) для
 * {@code @ApplicationModuleListener} ({@code @Async + @TransactionalEventListener}). Без
 * {@link TracingTaskDecorator} при переключении потока trace-контекст теряется:
 * {@code ExecutionService#processEvent} начинал бы новый trace вместо того, чтобы стать
 * дочерним span'ом входящего HTTP-запроса — сквозная трассировка "входящее сообщение → движок
 * ECA → исходящее" не работала бы в production.
 *
 * <p>{@link ThreadPoolTaskExecutorCustomizer} подхватывается автоматически через
 * {@code TaskExecutionAutoConfiguration} (Spring Boot 3.x) и применяется к
 * {@code applicationTaskExecutor} до его использования. В тестах ({@code BaseIntegrationTest})
 * стандартный {@code applicationTaskExecutor} заменён {@code SyncTaskExecutor} через
 * {@code @Primary} — этот кастомайзер на {@code SyncTaskExecutor} не влияет, поскольку
 * тот не является {@code ThreadPoolTaskExecutor}. В тестах смены потока нет, OTel-контекст
 * доступен в том же потоке без decorator'а.
 *
 * <p><b>Опциональная зависимость от {@link ContextSnapshotFactory}:</b> в тестах без
 * {@code @AutoConfigureObservability} стандартный {@code @SpringBootTest} применяет
 * {@code DisableObservabilityContextCustomizer}, который исключает
 * {@code ObservabilityAutoConfiguration} и не создаёт {@code ContextSnapshotFactory}.
 * Поэтому зависимость обёрнута в {@code ObjectProvider} — если бин недоступен, decorator
 * не устанавливается и executor работает без трассировки (корректно для unit/интег. тестов).
 *
 * @see TracingTaskDecorator
 */
@Configuration
public class ObservabilityAsyncConfig {

    /**
     * Регистрирует {@link TracingTaskDecorator} на {@code applicationTaskExecutor}, чтобы
     * OTel trace-контекст (текущий span, baggage) автоматически пробрасывался из потока-публикатора
     * события в поток-подписчик ({@code @ApplicationModuleListener} worker).
     *
     * <p>{@code ObjectProvider<ContextSnapshotFactory>} используется вместо прямой инъекции:
     * в тестах без наблюдаемости фабрика не регистрируется в контексте — с прямой инъекцией
     * Spring кинул бы {@code NoSuchBeanDefinitionException} при старте. С {@code ObjectProvider}
     * отсутствие бина возвращает {@code null} через {@code getIfAvailable()}: decorator
     * тихо не устанавливается.
     *
     * @param snapshotFactoryProvider провайдер фабрики; доступен в production и в тестах с
     *        {@code @AutoConfigureObservability}; {@code null}-safe для остальных тестов.
     */
    @Bean
    public ThreadPoolTaskExecutorCustomizer tracingTaskExecutorCustomizer(
            ObjectProvider<ContextSnapshotFactory> snapshotFactoryProvider) {
        return executor -> {
            ContextSnapshotFactory snapshotFactory = snapshotFactoryProvider.getIfAvailable();
            if (snapshotFactory != null) {
                executor.setTaskDecorator(new TracingTaskDecorator(snapshotFactory));
            }
        };
    }
}
