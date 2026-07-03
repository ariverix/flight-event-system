package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.cluster.ApplicationReadiness;
import ru.protectinfotrans.eca.cluster.LeaderElection;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-5: тонкий {@code @Scheduled}-триггер опроса просроченных WAIT-таймаутов.
 *
 * <p>Этот компонент сам НЕ содержит никакой бизнес-логики и никакого состояния, влияющего на
 * корректность — он лишь периодически вызывает {@link ExecutionService#checkWaitTimeouts()}.
 * Durable single-fire (ровно один срабатывание таймаута даже при нескольких репликах backend
 * или перекрытии тиков) обеспечивается атомарным claim'ом В БД ({@code claimExpiredTimeout} /
 * {@code claimAndAdvanceTimeout}) — НЕ этим планировщиком. Поэтому намеренно не вводится никакой
 * leader election (P6-1) — если бы корректность зависела от того, "какая реплика сейчас главная",
 * она была бы зоной P6-1; здесь корректность не зависит от того, сколько реплик одновременно
 * вызывают {@code checkWaitTimeouts()} прямо сейчас.
 *
 * <p>Выделен в отдельный {@code @Component} (а не как {@code @Scheduled}-метод внутри
 * {@link ExecutionService}), чтобы вызов {@code checkWaitTimeouts() -> claimAndAdvanceTimeout(...)}
 * физически проходил через Spring AOP-прокси {@link ExecutionService} как вызов ИЗ ДРУГОГО бина —
 * без этого вызов изнутри того же класса был бы self-invocation, и {@code @Transactional(REQUIRES_NEW)}
 * на {@code claimAndAdvanceTimeout} не сработал бы (см. подробный javadoc в {@link ExecutionService}).
 */
@Component
@Slf4j
public class WaitTimeoutScheduler {

    private final ExecutionService executionService;
    private final LeaderElection leaderElection;
    private final ApplicationReadiness applicationReadiness;
    private final AtomicLong lastPollDurationMs;

    public WaitTimeoutScheduler(ExecutionService executionService, LeaderElection leaderElection,
                                ApplicationReadiness applicationReadiness, MeterRegistry meterRegistry) {
        this.executionService = executionService;
        this.leaderElection = leaderElection;
        this.applicationReadiness = applicationReadiness;
        this.lastPollDurationMs = meterRegistry.gauge("eca.execution.wait_timeout_poll.duration_ms", new AtomicLong(0));
    }

    /**
     * каждые 10 сек опрашиваем просроченные WAIT-шаги (durable claim в БД, см. ExecutionService).
     *
     * <p>P6-1: автоматический тик выполняется ТОЛЬКО на реплике-лидере (leader election на PostgreSQL,
     * {@link LeaderElection}) — чтобы в кластере не опрашивали все реплики сразу. Корректность
     * single-fire от этого не зависит: атомарный claim в БД ({@code claimAndAdvanceTimeout}) — это
     * defense-in-depth даже при кратком раздвоении лидерства.
     */
    @Scheduled(fixedRate = 10000)
    public void pollWaitTimeouts() {
        // P2-3: не тикаем до готовности приложения (ApplicationReadyEvent) — гигиена, чтобы поллер
        // не обращался к схеме до её готовности на старте.
        if (!applicationReadiness.isReady()) {
            return;
        }
        if (!leaderElection.isLeader()) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            executionService.checkWaitTimeouts();
        } catch (Exception e) {
            // один сбойный тик не должен останавливать @Scheduled навсегда
            log.error("checkWaitTimeouts tick failed — will retry on next scheduled run", e);
        } finally {
            lastPollDurationMs.set(System.currentTimeMillis() - start);
        }
    }
}
