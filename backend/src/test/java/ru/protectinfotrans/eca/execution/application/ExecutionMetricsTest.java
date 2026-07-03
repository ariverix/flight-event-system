package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-7/P6-1 (V38): проверка проводки метрики
 * {@code eca.execution.start.duplicate_rejected}. Инкремент из
 * {@link ExecutionMetrics#recordDuplicateStartRejected()} вызывается в
 * {@code ExecutionService.startExecutionDeduplicated} при проигрыше конкурентной гонки уникальному
 * индексу. Здесь — детерминированная проверка самого счётчика (реальная гонка проверяется
 * интеграционно в {@code P1_9_ConcurrentStartDedupScenarioIntTest}, но она вероятностна по числу
 * срабатываний — поэтому корректность счётчика фиксируется отдельным unit-тестом).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionMetrics — счётчик отклонённых дублирующих стартов (V38)")
class ExecutionMetricsTest {

    @Mock
    private ExecutionRepositoryPort executionRepository;

    @Test
    @DisplayName("recordDuplicateStartRejected инкрементит eca.execution.start.duplicate_rejected")
    void recordsDuplicateStartRejected() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExecutionMetrics metrics = new ExecutionMetrics(registry, executionRepository);

        assertThat(registry.get("eca.execution.start.duplicate_rejected").counter().count())
                .isEqualTo(0.0);

        metrics.recordDuplicateStartRejected();
        metrics.recordDuplicateStartRejected();

        assertThat(registry.get("eca.execution.start.duplicate_rejected").counter().count())
                .isEqualTo(2.0);
    }
}
