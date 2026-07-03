package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

/**
 * P5-1: метрики движка выполнения (Micrometer → Prometheus).
 * <ul>
 *   <li>{@code eca.execution.instances.active} (gauge) — число активных (RUNNING/WAITING)
 *       инстансов; опрашивается на каждый scrape через {@code countActive()};</li>
 *   <li>{@code eca.execution.wait_timeout.fired} (counter) — сработавшие WAIT-таймауты
 *       (инкремент только при УСПЕШНОМ single-fire claim, см. {@code ExecutionService#checkWaitTimeouts});</li>
 *   <li>{@code eca.execution.start.duplicate_rejected} (counter) — отклонённые дублирующие старты
 *       инстанса, проигравшие гонку уникальному индексу idx_exec_dedup_trigger_unique (V38, P1-7/P6-1):
 *       ненулевой рост = конкурентная at-least-once доставка одного сообщения на несколько реплик
 *       (защита сработала, дубль не создан) — см. {@code ExecutionService#startExecutionDeduplicated};</li>
 *   <li>{@code eca.execution.event.duration} (timer) — латентность обработки события движком
 *       (p95/p99 включены через гистограмму в application.yml).</li>
 * </ul>
 * Вынесено в отдельный бин (а не в {@code ExecutionService}), чтобы gauge самрегистрировался и
 * наблюдаемость не размазывалась по бизнес-логике; {@code ExecutionService} зовёт counter/timer.
 */
@Component
public class ExecutionMetrics {

    private final Counter waitTimeoutFired;
    private final Counter duplicateStartRejected;
    private final Timer eventProcessingTimer;

    public ExecutionMetrics(MeterRegistry meterRegistry, ExecutionRepositoryPort executionRepository) {
        meterRegistry.gauge("eca.execution.instances.active", executionRepository,
                repo -> (double) repo.countActive());
        this.waitTimeoutFired = meterRegistry.counter("eca.execution.wait_timeout.fired");
        this.duplicateStartRejected = meterRegistry.counter("eca.execution.start.duplicate_rejected");
        this.eventProcessingTimer = meterRegistry.timer("eca.execution.event.duration");
    }

    public void recordWaitTimeoutFired() {
        waitTimeoutFired.increment();
    }

    /** P1-7/P6-1 (V38): отклонён дублирующий старт, проигравший гонку уникальному индексу. */
    public void recordDuplicateStartRejected() {
        duplicateStartRejected.increment();
    }

    public Timer eventProcessingTimer() {
        return eventProcessingTimer;
    }
}
