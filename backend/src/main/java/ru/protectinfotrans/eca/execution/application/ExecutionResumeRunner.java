package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P1-4 (resume после рестарта). При старте приложения восстанавливает незавершённые
 * экземпляры выполнения (RUNNING/WAITING) из {@code execution_instances} — поведение
 * персистентного движка, переживающего рестарт сервиса, а не in-memory автомат.
 *
 * <p><b>Почему {@link ApplicationRunner}, а не {@code @PostConstruct}:</b> {@code @PostConstruct}
 * срабатывает на этапе создания бина — до того, как контекст Spring полностью готов (другие бины,
 * особенно {@code DataSource}/Flyway-миграции и {@code ApplicationModuleListener}-инфраструктура
 * Spring Modulith, могут быть ещё не до конца инициализированы). {@link ApplicationRunner} вызывается
 * Spring Boot ПОСЛЕ полного построения {@code ApplicationContext} (фактически на {@code ApplicationReadyEvent}),
 * когда БД доступна, миграции Flyway уже применены, и слушатели событий зарегистрированы — резюм
 * не рискует пропустить событие, которое могло прилететь "слишком рано".
 *
 * <h2>Что восстанавливается</h2>
 * <ul>
 *   <li><b>WAITING</b> — НЕ требует активного действия здесь: WAIT-окно (waitStartedAt/waitTimeoutAt)
 *       и "from this point only" точка отсчёта полностью персистентны в самой строке
 *       {@code execution_instances} (см. P1-3 — context JSONB переживает рестарт) и читаются заново
 *       из БД каждым обработчиком события ({@code ExecutionService#processWaitingInstances}) и каждым
 *       тиком {@code @Scheduled checkWaitTimeouts}. После рестарта оба эти пути снова активны сами по
 *       себе (Spring поднимает {@code @ApplicationModuleListener} и {@code @Scheduled} вместе с контекстом) —
 *       никакого "переигрывания" не требуется. Этот runner только логирует и считает их для метрики.</li>
 *   <li><b>RUNNING</b> — может означать, что процесс упал между сохранением указателя текущего шага
 *       и обработкой его результата (см. {@code ExecutionService#resumeRunningInstanceAfterRestart}).
 *       Чтобы не "зависнуть" навечно, текущий шаг повторно прогоняется детерминированно через тот же
 *       пайплайн, что и обычный переход.</li>
 *   <li><b>COMPLETED/ABORTED</b> — не воскрешаются: {@code findAllActive()} их не возвращает.</li>
 * </ul>
 *
 * <h2>Multi-replica (P6-1)</h2>
 * При нескольких репликах backend каждая выполнит этот runner на своём старте и повторно прогонит
 * RUNNING-инстансы независимо — для ACTION с внешним побочным эффектом (uplink/ground) это риск
 * дублирования сверх того, что описан в {@code resumeRunningInstanceAfterRestart}. Сейчас умышленно
 * не вводится никакая распределённая координация (leader election, distributed lock) — это зона
 * P6-1. Single-node resume такому будущему не противоречит: вся работа идёт через обычные
 * репозиторий/сервис вызовы в транзакции, без in-memory состояния раннера, которое потребовалось
 * бы переделывать под лидера.
 */
@Component
@Slf4j
public class ExecutionResumeRunner implements ApplicationRunner {

    private final ExecutionRepositoryPort executionRepository;
    private final ExecutionService executionService;

    // Micrometer: gauge привязывается к мутируемому держателю значения один раз в конструкторе
    // (стандартная идиома для "push-once, update-many" метрик) — meterRegistry.gauge(name, number)
    // с НОВЫМ объектом Number при каждом вызове run() регистрацию игнорирует (gauge уже существует
    // под этим именем), и значение осталось бы навсегда привязано к первому объекту. AtomicLong
    // как держатель значения позволяет run() обновлять текущее значение на каждом вызове.
    private final AtomicLong resumedInstancesGauge;
    private final AtomicLong resumedRunningGauge;
    private final AtomicLong resumedWaitingGauge;

    public ExecutionResumeRunner(ExecutionRepositoryPort executionRepository,
                                  ExecutionService executionService,
                                  MeterRegistry meterRegistry) {
        this.executionRepository = executionRepository;
        this.executionService = executionService;
        this.resumedInstancesGauge = meterRegistry.gauge("eca.execution.resumed.instances", new AtomicLong(0));
        this.resumedRunningGauge = meterRegistry.gauge("eca.execution.resumed.running", new AtomicLong(0));
        this.resumedWaitingGauge = meterRegistry.gauge("eca.execution.resumed.waiting", new AtomicLong(0));
    }

    @Override
    public void run(ApplicationArguments args) {
        // ВАЖНО: run() сам НЕ транзакционен — каждый findAllActive()/resumeRunningInstanceAfterRestart
        // получает СОБСТВЕННУЮ транзакцию (см. ExecutionService#resumeRunningInstanceAfterRestart,
        // REQUIRES_NEW). Если бы весь цикл шёл в одной общей транзакции/Hibernate-сессии, сбой одного
        // инстанса, инвалидирующий сессию на flush (constraint violation, optimistic lock и т.п.,
        // а не просто бизнес-исключение), оставлял бы EntityManager в невалидном состоянии для
        // обработки ВСЕХ последующих инстансов в этом же цикле — try/catch ниже ловит исключение
        // на Java-уровне, но это не спасает от уже испорченной транзакции/сессии. Разделение на
        // отдельные транзакции на инстанс — единственный способ гарантировать реальную изоляцию.
        List<ExecutionInstance> active = executionRepository.findAllActive();

        long runningCount = active.stream().filter(i -> i.getStatus() == ExecutionStatus.RUNNING).count();
        long waitingCount = active.stream().filter(i -> i.getStatus() == ExecutionStatus.WAITING).count();

        log.info("Resume: found {} unfinished execution instance(s) on startup ({} RUNNING, {} WAITING)",
                active.size(), runningCount, waitingCount);

        for (ExecutionInstance instance : active) {
            if (instance.getStatus() == ExecutionStatus.RUNNING) {
                try {
                    executionService.resumeRunningInstanceAfterRestart(instance);
                } catch (Exception e) {
                    // один сбойный инстанс не должен останавливать старт приложения
                    // и не должен мешать восстановлению остальных
                    log.error("Resume: failed to resume RUNNING instance {} — leaving it for next attempt",
                            instance.getId(), e);
                }
            } else {
                log.info("Resume: WAITING instance {} (step {}) left as is — wait window/timeout already "
                                + "persisted, will be served by event listener / scheduled timeout check",
                        instance.getId(), instance.getCurrentStepIndex());
            }
        }

        resumedInstancesGauge.set(active.size());
        resumedRunningGauge.set(runningCount);
        resumedWaitingGauge.set(waitingCount);
    }
}
