package ru.protectinfotrans.eca.execution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-5: Durable-планировщик WAIT/таймаутов — атомарный claim в БД, не in-memory.
 *
 * <p>Ключевой тест пакета — {@link SingleFireUnderConcurrencyTests#timeoutFiresExactlyOnceUnderConcurrentClaims()}:
 * доказывает, что переход по истёкшему WAIT-таймауту срабатывает РОВНО ОДИН раз, даже когда
 * несколько потоков (имитация нескольких реплик backend или перекрывшихся тиков
 * {@code @Scheduled}) одновременно пытаются обработать один и тот же просроченный инстанс.
 * Тест работает на реальном PostgreSQL (требует {@code FOR UPDATE}-семантику условного UPDATE
 * под конкурентной нагрузкой, не воспроизводимую на H2/моках).
 *
 * <p>Второй тест — переживание рестарта: WAIT с {@code wait_timeout_at} в прошлом (как будто
 * таймаут наступил во время простоя сервиса) должен сработать один раз при первом вызове
 * планировщика после "перезапуска", и не должен повторно сработать на втором тике.
 *
 * Демо-борт VP-BQR — единый стиль с {@code EcaParityScenarioIntTest}.
 */
@Slf4j
@DisplayName("P1-5: Durable WAIT timeout scheduler — single-fire claim")
class P1_5_WaitTimeoutSingleFireScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createWaitSequence(String name, int timeoutSeconds) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "Тест P1-5 single-fire", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Ждать ACK (никогда не придёт)",
                StepType.WAIT,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                timeoutSeconds,
                TransitionAction.END, null, false,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private ExecutionInstance findInstance(Long sequenceId, String aircraftId) {
        return executionRepository.findActiveByAircraftId(aircraftId).stream()
                .filter(i -> i.getSequenceId().equals(sequenceId))
                .findFirst()
                .or(() -> executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                        .getContent().stream()
                        .filter(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()))
                        .findFirst())
                .orElseThrow(() -> new AssertionError("No instance found for sequence " + sequenceId));
    }

    private int countStepExecutions(Long instanceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM step_executions WHERE execution_instance_id = ?",
                Integer.class, instanceId);
        return count == null ? 0 : count;
    }

    // ============================================================
    // 1. Single-fire под конкуренцией (ключевой приёмочный тест P1-5)
    // ============================================================
    @Nested
    @DisplayName("Single-fire под конкуренцией (реальный Postgres)")
    class SingleFireUnderConcurrencyTests {

        @Test
        @DisplayName("N параллельных вызовов claimAndAdvanceTimeout на один просроченный инстанс — переход срабатывает ровно один раз")
        void timeoutFiresExactlyOnceUnderConcurrentClaims() throws InterruptedException {
            Long sequenceId = createWaitSequence("WAIT single-fire concurrency", 300);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // принудительно истекаем таймаут (детерминированно, без реального sleep)
            LocalDateTime expiredAt = LocalDateTime.now().minusSeconds(5);
            instance.setWaitTimeoutAt(expiredAt);
            executionRepository.save(instance);

            // Имитация конкуренции: N потоков одновременно вызывают ПОЛНЫЙ путь production-кода
            // claimAndAdvanceTimeout(id, expectedTimeout) для ОДНОГО И ТОГО ЖЕ инстанса — как если
            // бы несколько реплик backend или перекрывшиеся тики @Scheduled нашли один и тот же
            // просроченный кандидат в findWaitingWithExpiredTimeout и пытались его обработать
            // параллельно. claimAndAdvanceTimeout сам внутри делает claim + (если выиграл) бизнес-
            // переход — это ТОТ ЖЕ метод, который вызывает реальный checkWaitTimeouts в проде.
            int concurrentAttempts = 8;
            ExecutorService pool = Executors.newFixedThreadPool(concurrentAttempts);
            CountDownLatch readyLatch = new CountDownLatch(concurrentAttempts);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger exceptions = new AtomicInteger(0);

            try {
                for (int i = 0; i < concurrentAttempts; i++) {
                    pool.submit(() -> {
                        try {
                            readyLatch.countDown();
                            startLatch.await(10, TimeUnit.SECONDS);
                            executionService.claimAndAdvanceTimeout(instance.getId(), expiredAt);
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                            log.error("Concurrent claimAndAdvanceTimeout attempt failed unexpectedly", e);
                        }
                    });
                }

                // ждём, чтобы все потоки были готовы к старту — максимизируем реальное перекрытие
                assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
                startLatch.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(exceptions.get()).isZero();

            // КЛЮЧЕВАЯ ПРОВЕРКА: несмотря на 8 конкурентных попыток обработать один и тот же
            // просроченный таймаут, бизнес-переход (advanceExecution с FAILURE) выполнился
            // ровно один раз — инстанс перешёл в терминальный статус (onFailure=ABORT в фикстуре)
            // и в истории шагов записан РОВНО ОДИН StepExecution, не восемь.
            ExecutionInstance updated = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(countStepExecutions(instance.getId())).isEqualTo(1);

            // Повторный claim после того, как переход уже выполнен, больше не проходит —
            // подтверждает, что таймаут окончательно погашен, а не "временно занят".
            assertThat(executionRepository.claimExpiredTimeout(instance.getId(), expiredAt)).isFalse();
        }

        @Test
        @DisplayName("полный цикл checkWaitTimeouts(), вызванный дважды параллельно — переход выполняется один раз")
        void checkWaitTimeoutsCalledConcurrentlyResolvesOnce() throws InterruptedException {
            Long sequenceId = createWaitSequence("WAIT single-fire full-cycle concurrency", 300);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            instance.setWaitTimeoutAt(LocalDateTime.now().minusSeconds(5));
            executionRepository.save(instance);

            int concurrentTicks = 4;
            ExecutorService pool = Executors.newFixedThreadPool(concurrentTicks);
            CountDownLatch readyLatch = new CountDownLatch(concurrentTicks);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger exceptions = new AtomicInteger(0);

            try {
                for (int i = 0; i < concurrentTicks; i++) {
                    pool.submit(() -> {
                        try {
                            readyLatch.countDown();
                            startLatch.await(10, TimeUnit.SECONDS);
                            // checkWaitTimeouts() — полный путь, как у реального @Scheduled тика
                            executionService.checkWaitTimeouts();
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                            log.error("Concurrent checkWaitTimeouts tick failed unexpectedly", e);
                        }
                    });
                }

                assertThat(readyLatch.await(10, TimeUnit.SECONDS)).isTrue();
                startLatch.countDown();
            } finally {
                pool.shutdown();
                assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(exceptions.get()).isZero();

            ExecutionInstance updated = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            // независимо от того, сколько "тиков планировщика" перекрылись — переход по
            // таймауту записан в историю шагов ровно один раз
            assertThat(countStepExecutions(instance.getId())).isEqualTo(1);
        }
    }

    // ============================================================
    // 2. Переживание рестарта: таймаут, наступивший во время простоя
    // ============================================================
    @Nested
    @DisplayName("Переживание рестарта сервиса")
    class SurvivesRestartTests {

        @Test
        @DisplayName("таймаут, истёкший во время простоя сервиса, срабатывает один раз после старта планировщика")
        void timeoutExpiredDuringDowntimeFiresOnceAfterStart() {
            Long sequenceId = createWaitSequence("WAIT survives restart", 300);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // Эмулируем простой сервиса: таймаут "наступил" 10 минут назад, пока сервис не работал.
            // Персистентность wait_timeout_at в execution_instances (P1-3) — единственное, что
            // нужно, чтобы это пережить рестарт: при реальном перезапуске процесса строка останется
            // такой же в БД, resume (P1-4) восстановит инстанс в WAITING, а первый же тик
            // планировщика после старта (эмулируется прямым вызовом checkWaitTimeouts ниже) его доберёт.
            LocalDateTime expiredDuringDowntime = LocalDateTime.now().minusMinutes(10);
            instance.setWaitTimeoutAt(expiredDuringDowntime);
            executionRepository.save(instance);

            // "Старт сервиса" — первый вызов планировщика после поднятия процесса
            executionService.checkWaitTimeouts();

            ExecutionInstance afterFirstTick = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterFirstTick.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(countStepExecutions(instance.getId())).isEqualTo(1);

            // Повторный тик планировщика (как будто прошло ещё 10 секунд) не должен затронуть
            // уже завершённый (ABORTED) инстанс — findWaitingWithExpiredTimeout его больше не вернёт.
            executionService.checkWaitTimeouts();

            ExecutionInstance afterSecondTick = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterSecondTick.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(countStepExecutions(instance.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("COMPLETED/ABORTED инстансы не трогаются повторными тиками планировщика")
        void completedAndAbortedInstancesAreNotReprocessed() {
            Long sequenceId = createWaitSequence("WAIT terminal instances untouched", 300);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            instance.setWaitTimeoutAt(LocalDateTime.now().minusMinutes(1));
            executionRepository.save(instance);

            executionService.checkWaitTimeouts();

            ExecutionInstance aborted = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(aborted.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            int stepExecutionsAfterAbort = countStepExecutions(instance.getId());

            // несколько дополнительных тиков — терминальный инстанс не должен возвращаться
            // в findWaitingWithExpiredTimeout (он больше не WAITING) и не должен порождать
            // новые StepExecution
            executionService.checkWaitTimeouts();
            executionService.checkWaitTimeouts();

            ExecutionInstance stillAborted = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(stillAborted.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(countStepExecutions(instance.getId())).isEqualTo(stepExecutionsAfterAbort);
        }
    }
}
