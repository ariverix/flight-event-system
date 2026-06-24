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
 *   <li>{@code eca.execution.event.duration} (timer) — латентность обработки события движком
 *       (p95/p99 включены через гистограмму в application.yml).</li>
 * </ul>
 * Вынесено в отдельный бин (а не в {@code ExecutionService}), чтобы gauge самрегистрировался и
 * наблюдаемость не размазывалась по бизнес-логике; {@code ExecutionService} зовёт counter/timer.
 */
@Component
public class ExecutionMetrics {

    private final Counter waitTimeoutFired;
    private final Timer eventProcessingTimer;

    public ExecutionMetrics(MeterRegistry meterRegistry, ExecutionRepositoryPort executionRepository) {
        meterRegistry.gauge("eca.execution.instances.active", executionRepository,
                repo -> (double) repo.countActive());
        this.waitTimeoutFired = meterRegistry.counter("eca.execution.wait_timeout.fired");
        this.eventProcessingTimer = meterRegistry.timer("eca.execution.event.duration");
    }

    public void recordWaitTimeoutFired() {
        waitTimeoutFired.increment();
    }

    public Timer eventProcessingTimer() {
        return eventProcessingTimer;
    }
}
