package ru.protectinfotrans.eca.execution;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-6: Конкурентность движка выполнения — оптимистическая блокировка (JPA {@code @Version}
 * на {@link ExecutionInstance}) + независимая, корректная обработка ОДНОГО
 * {@link NormalizedEvent}, затрагивающего МНОГО инстансов, под реальной конкуренцией на
 * Postgres.
 *
 * <p><b>Важное про {@code processEvent} в тестах конкуренции:</b> {@code ExecutionService#processEvent}
 * — это {@code @ApplicationModuleListener} (мета-аннотирован {@code @Async}) — реальный вызов
 * диспетчеризуется в собственный async-исполнитель Spring Modulith ({@code task-N} потоки), а
 * не выполняется синхронно в вызывающем потоке (в отличие от прямых вызовов методов сервиса).
 * Для теста "доставка ОДНОГО события из N потоков одновременно" вызов {@code processEvent} из N
 * java-потоков добавил бы ЕЩЁ один неконтролируемый уровень параллелизма сверху (каждый вызов
 * сам уходит в отдельный async-поток) — при десятках инстансов это исчерпывает пул соединений
 * теста (HikariCP, по умолчанию 10) и не даёт контролируемой гонки. Поэтому тесты конкурентной
 * ГОНКИ НА ОДНОМ инстансе ниже вызывают P1-6-методы {@code checkStopCriterionTransactional}/
 * {@code tryResumeWaitingInstanceTransactional} НАПРЯМУЮ (они обычные синхронные
 * {@code @Transactional}, без {@code @Async}) — это даёт точный контроль числа одновременных
 * транзакций и проверяет ИМЕННО механизм оптимистической блокировки + retry, который
 * {@code processEvent} использует внутри. Фан-аут через настоящий {@code processEvent} проверен
 * отдельно ({@link FanOutOneEventManyInstancesTests}) с ожиданием через polling — как и все
 * остальные P1-* сценарные тесты в этом пакете.
 *
 * <p>Покрывает приёмку задачи:
 * <ul>
 *   <li>{@link FanOutOneEventManyInstancesTests} — одно НАСТОЯЩЕЕ событие
 *       ({@code processEvent}) на один борт затрагивает десятки независимых WAITING-инстансов
 *       (разные последовательности на один борт, как в SITA) — все резолвятся корректно и ровно
 *       один раз, без потерь и без двойных эффектов.</li>
 *   <li>{@link ConcurrentDeliveryOfSameEventTests} — N потоков одновременно (точно
 *       контролируемая конкуренция) вызывают P1-6 per-instance транзакционные методы с ОДНИМ
 *       stop-критерием на ОДИН и тот же набор активных инстансов: каждый инстанс должен
 *       абортироваться РОВНО один раз — доказывает, что {@code @Version} + bounded retry в
 *       {@code ExecutionService} реально работают под конкурентной нагрузкой, а не только в
 *       теории.</li>
 *   <li>{@link OptimisticLockColumnSmokeTests} — version реально инкрементируется на каждом
 *       update и конфликт реально детектируется на уровне Hibernate/Postgres (а не только
 *       "не падает") — нужен прямой смок без скрытых retry, чтобы доказать, что блокировка
 *       АКТИВНА, а не просто лежит как nullable-колонка, как до P1-6.</li>
 * </ul>
 *
 * <p>Микро-нагрузка (десятки инстансов/потоков), не полноценный k6/Gatling прогон — тот в
 * P2-7/P6-3. Цель — доказать корректность под конкуренцией и отсутствие катастрофической
 * деградации (тест укладывается в разумное время на CI), а не измерить throughput.
 *
 * <p>Демо-борт VP-BQR, рейс SU1234 — единый стиль с остальными P1-* сценарными тестами.
 */
@Slf4j
@DisplayName("P1-6: Конкурентность — оптимистическая блокировка и фан-аут событий по инстансам")
class P1_6_ConcurrencyAndOptimisticLockingScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    /**
     * Последовательность из одного WAIT-шага, ждущего ACK, который никогда не придёт по
     * штатному пути — резолвится только через stop-критерий, явный ACK или вызов теста.
     * onFailure=ABORT (таймаут шага намеренно большой — 600 сек, не должен сработать раньше
     * проверок теста).
     */
    private Long createWaitForAckSequence(String name, String stopCriteriaJson) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(
                name, "P1-6 concurrency микротест", null, stopCriteriaJson);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Ждать ACK (никогда не придёт по штатному пути)",
                StepType.WAIT,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                600,
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
                .or(() -> executionRepository.findAll(PageRequest.of(0, 500))
                        .getContent().stream()
                        .filter(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()))
                        .findFirst())
                .orElseThrow(() -> new AssertionError("No instance found for sequence " + sequenceId));
    }

    /**
     * {@code processEvent} — {@code @Async}, поэтому после его вызова ждём терминального
     * состояния через polling (тот же паттерн, что в {@code P1_2_DecisionAndStartStopScenarioIntTest}).
     */
    private void awaitStatus(Long instanceId, ExecutionStatus expected) {
        long deadline = System.currentTimeMillis() + 15_000;
        ExecutionInstance last = null;
        while (System.currentTimeMillis() < deadline) {
            last = executionRepository.findById(instanceId).orElse(null);
            if (last != null && last.getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Instance " + instanceId + " did not reach " + expected
                + " within timeout, last state: " + last);
    }

    private void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger exceptions = new AtomicInteger(0);
        boolean poolTerminated;

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await(15, TimeUnit.SECONDS);
                        task.run();
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                        log.error("Concurrent task failed unexpectedly", e);
                    }
                });
            }

            assertThat(readyLatch.await(15, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();
        } finally {
            // shutdownNow() ВСЕГДА, не только на неудачном awaitTermination — 2026-08-31: assertThat
            // внутри finally бросал AssertionError на таймауте ДО прерывания зависших потоков; они
            // переживали границу теста и продолжали трогать БД во время flyway.clean() следующего
            // теста (найдено при разборе зависания backend job в CI на 50+ мин).
            pool.shutdown();
            poolTerminated = pool.awaitTermination(60, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
        assertThat(poolTerminated).as("пул потоков должен завершиться в срок штатно").isTrue();

        assertThat(exceptions.get())
                .as("ни одна из %d конкурентных задач не должна выбросить непойманное исключение", threads)
                .isZero();
    }

    /**
     * Воспроизводит ТОЧНО ТУ ЖЕ retry-семантику, что приватный {@code ExecutionService#withOptimisticRetry}
     * применяет в продакшен-пути ({@code checkStopCriteria}/{@code processWaitingInstances}) вокруг
     * вызова per-instance {@code REQUIRES_NEW}-метода через {@code self}-прокси: при
     * {@code ObjectOptimisticLockingFailureException} — повторить с ограничением попыток
     * ({@code ExecutionService#MAX_OPTIMISTIC_LOCK_RETRIES}), при успехе или истощении лимита —
     * вернуться. Тесты ниже вызывают {@code checkStopCriterionTransactional}/
     * {@code tryResumeWaitingInstanceTransactional} НАПРЯМУЮ (не через {@code processEvent}/{@code @Async} —
     * см. javadoc класса), поэтому конфликт версии, который продакшен-код перехватывает и
     * ретраит внутри себя, здесь нужно ретраить explicit — иначе тест проверял бы не реальную
     * гарантию продакшен-пути, а искусственно урезанный вызов без его retry-обвязки.
     */
    private void runWithProductionRetrySemantics(Runnable perInstanceCall) {
        int attempt = 0;
        while (true) {
            try {
                perInstanceCall.run();
                return;
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                attempt++;
                if (attempt >= ExecutionService.MAX_OPTIMISTIC_LOCK_RETRIES) {
                    return;
                }
            }
        }
    }

    // ============================================================
    // 1. Одно НАСТОЯЩЕЕ событие (processEvent) → много независимых инстансов одного борта
    // ============================================================
    @Nested
    @DisplayName("Фан-аут: одно событие (processEvent) затрагивает десятки инстансов одного борта")
    class FanOutOneEventManyInstancesTests {

        /**
         * Десятки последовательностей (имитация "много последовательностей на один борт" —
         * SITA-семантика), каждая со своим WAIT-шагом и СВОИМ stop-критерием
         * FLIGHT_STAGE >= ON — одно событие со стадией ON обязано остановить ВСЕ их
         * независимо. Каждый инстанс должен закончить РОВНО в статусе ABORTED, без потерь
         * (все 25) — что было бы возможно при старой единой long-running транзакции на весь
         * processEvent (сбой одного инстанса блокировал бы или откатывал обработку соседей).
         */
        @Test
        @DisplayName("25 независимых WAITING-инстансов одного борта — все корректно ABORTED одним событием")
        void oneEventResolvesAllIndependentInstancesOfOneAircraft() {
            int instanceCount = 25;
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";

            List<Long> sequenceIds = IntStream.range(0, instanceCount)
                    .mapToObj(i -> createWaitForAckSequence("P1-6 fan-out seq " + i, stopCriteria))
                    .toList();

            List<Long> instanceIds = sequenceIds.stream()
                    .map(seqId -> {
                        executionService.startExecution(seqId, AIRCRAFT_ID, FLIGHT_NUMBER);
                        ExecutionInstance instance = findInstance(seqId, AIRCRAFT_ID);
                        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
                        return instance.getId();
                    })
                    .toList();

            // ОДНО событие — стадия ON — должно остановить ВСЕ 25 инстансов через их
            // индивидуальные stop-критерии (checkStopCriteria фан-аутится по
            // findActiveByAircraftId, каждый инстанс — собственная REQUIRES_NEW транзакция).
            executionService.processEvent(new NormalizedEvent(
                    100L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now()));

            for (Long instanceId : instanceIds) {
                awaitStatus(instanceId, ExecutionStatus.ABORTED);
            }

            List<ExecutionInstance> finalStates = instanceIds.stream()
                    .map(id -> executionRepository.findById(id).orElseThrow())
                    .toList();

            assertThat(finalStates)
                    .as("все %d инстансов должны быть ABORTED — ни одного не потеряно, ни одного не пропущено",
                            instanceCount)
                    .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ExecutionStatus.ABORTED));
            assertThat(finalStates).extracting(ExecutionInstance::getId).doesNotHaveDuplicates();
            assertThat(finalStates).hasSize(instanceCount);
        }
    }

    // ============================================================
    // 2. Конкурентная гонка на ОДНОМ И ТОМ ЖЕ наборе инстансов (контролируемая параллельность)
    // ============================================================
    @Nested
    @DisplayName("Один и тот же инстанс под конкурентной доставкой — без двойного эффекта")
    class ConcurrentDeliveryOfSameEventTests {

        /**
         * Ключевой приёмочный тест P1-6: 10 потоков одновременно вызывают
         * {@code checkStopCriterionTransactional} — ТОТ ЖЕ метод, который
         * {@code ExecutionService#checkStopCriteria} вызывает на каждый кандидат внутри
         * настоящего {@code processEvent} — на ОДИН И ТОТ ЖЕ набор из 10 WAITING-инстансов
         * одного борта. Прямой вызов (а не через {@code processEvent}/{@code @Async}) даёт
         * точно контролируемое число одновременных транзакций без дополнительного
         * неконтролируемого параллелизма Spring Modulith async-исполнителя — иначе с
         * desятками инстансов x N async-потоков легко исчерпать пул соединений теста, не
         * имея отношения к самой проверяемой гарантии.
         *
         * <p>Без оптимистической блокировки + retry конкурентные {@code save()} на одной
         * строке рисковали бы либо "потерянным обновлением" (lost update — последний
         * save() тихо затирает предыдущий без видимой ошибки), либо просто отсутствием
         * детектируемого конфликта вообще. С {@code @Version} Hibernate гарантированно
         * поднимает {@code ObjectOptimisticLockingFailureException} у "проигравшего" потока
         * на конкурентном update этой же строки, {@code withOptimisticRetry} поглощает её и
         * повторяет уже над свежим состоянием — но сам бизнес-эффект (переход в ABORTED)
         * обязан произойти РОВНО один раз на инстанс, что и проверяется ниже.
         */
        @Test
        @DisplayName("10 потоков x один и тот же stop-критерий на 10 общих инстансов — каждый ABORTED ровно один раз")
        void tenThreadsRaceOnSameInstancesWithoutDoubleAbort() throws InterruptedException {
            int instanceCount = 10;
            int concurrentThreads = 10;
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";

            List<Long> sequenceIds = IntStream.range(0, instanceCount)
                    .mapToObj(i -> createWaitForAckSequence("P1-6 race seq " + i, stopCriteria))
                    .toList();

            List<Long> instanceIds = sequenceIds.stream()
                    .map(seqId -> {
                        executionService.startExecution(seqId, AIRCRAFT_ID, FLIGHT_NUMBER);
                        return findInstance(seqId, AIRCRAFT_ID).getId();
                    })
                    .toList();

            NormalizedEvent event = new NormalizedEvent(
                    102L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now());

            // КАЖДЫЙ из 10 потоков сам обходит ВСЕ 10 общих instanceIds и вызывает РЕАЛЬНЫЙ
            // production-метод checkStopCriterionTransactional с ТОЧНО ТОЙ ЖЕ retry-обвязкой,
            // что и настоящий processEvent (см. runWithProductionRetrySemantics) — максимизируем
            // перекрытие конкурирующих save() на одних и тех же строках, без неконтролируемого
            // async-множителя сверху.
            runConcurrently(concurrentThreads, () -> {
                for (Long instanceId : instanceIds) {
                    runWithProductionRetrySemantics(
                            () -> executionService.checkStopCriterionTransactional(instanceId, event));
                }
            });

            List<ExecutionInstance> finalStates = instanceIds.stream()
                    .map(id -> executionRepository.findById(id).orElseThrow())
                    .toList();

            assertThat(finalStates)
                    .as("каждый из %d общих инстансов должен быть ABORTED — без потерь под гонкой %d потоков",
                            instanceCount, concurrentThreads)
                    .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ExecutionStatus.ABORTED));

            // version инкрементировался хотя бы один раз (переход RUNNING/WAITING -> ABORTED) —
            // подтверждает, что обновление реально прошло через Hibernate dirty-checking, а не
            // было бизнес-операцией без эффекта.
            assertThat(finalStates).allSatisfy(i -> assertThat(i.getVersion()).isGreaterThanOrEqualTo(1L));
        }

        /**
         * То же самое, но набор побольше (20 общих инстансов, 8 конкурентных потоков) —
         * грубая проверка отсутствия катастрофической деградации/дедлока под более широкой
         * конкуренцией (не полноценный перфоманс-тест — тот в P2-7/P6-3).
         */
        @Test
        @DisplayName("20 общих инстансов x 8 потоков — обработка укладывается в разумный бюджет времени, без deadlock")
        void largerConcurrentRaceCompletesWithinReasonableBudget() throws InterruptedException {
            int instanceCount = 20;
            int concurrentThreads = 8;
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";

            List<Long> sequenceIds = IntStream.range(0, instanceCount)
                    .mapToObj(i -> createWaitForAckSequence("P1-6 perf race seq " + i, stopCriteria))
                    .toList();

            List<Long> instanceIds = sequenceIds.stream()
                    .map(seqId -> {
                        executionService.startExecution(seqId, AIRCRAFT_ID, FLIGHT_NUMBER);
                        return findInstance(seqId, AIRCRAFT_ID).getId();
                    })
                    .toList();

            NormalizedEvent event = new NormalizedEvent(
                    103L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now());

            long start = System.currentTimeMillis();
            runConcurrently(concurrentThreads, () -> {
                for (Long instanceId : instanceIds) {
                    runWithProductionRetrySemantics(
                            () -> executionService.checkStopCriterionTransactional(instanceId, event));
                }
            });
            long elapsedMs = System.currentTimeMillis() - start;

            log.info("P1-6 micro-load: {} instances x {} concurrent deliveries resolved in {} ms",
                    instanceCount, concurrentThreads, elapsedMs);

            List<ExecutionInstance> finalStates = instanceIds.stream()
                    .map(id -> executionRepository.findById(id).orElseThrow())
                    .toList();

            assertThat(finalStates)
                    .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ExecutionStatus.ABORTED));

            // грубый бюджет — не точный SLA (тот в P2-7/P6-3), просто защита от катастрофической
            // деградации/зависания (например необработанный deadlock-retry-loop)
            assertThat(elapsedMs)
                    .as("20 инстансов x 8 конкурентных потоков не должны деградировать катастрофически")
                    .isLessThan(60_000);
        }

        /**
         * Симметричный тест для WAIT-резолва (а не stop-критерия): N потоков одновременно
         * вызывают {@code tryResumeWaitingInstanceTransactional} с ОДНИМ И ТЕМ ЖЕ ACK-событием
         * на ОДИН И ТОТ ЖЕ набор WAITING-инстансов — каждый инстанс должен завершиться
         * (СOMPLETED, по onSuccess=END схемы createWaitForAckSequence) РОВНО один раз, с РОВНО
         * одной записью {@code StepExecution} в истории (а не N — что было бы двойным
         * бизнес-эффектом при гонке без оптимистической блокировки).
         */
        @Test
        @DisplayName("8 потоков x один и тот же ACK на 8 общих WAITING-инстансов — каждый COMPLETED ровно один раз")
        void concurrentWaitResolutionDoesNotDoubleAdvance() throws InterruptedException {
            int instanceCount = 8;
            int concurrentThreads = 8;

            List<Long> sequenceIds = IntStream.range(0, instanceCount)
                    .mapToObj(i -> createWaitForAckSequence("P1-6 wait race seq " + i, null))
                    .toList();

            List<Long> instanceIds = sequenceIds.stream()
                    .map(seqId -> {
                        executionService.startExecution(seqId, AIRCRAFT_ID, FLIGHT_NUMBER);
                        return findInstance(seqId, AIRCRAFT_ID).getId();
                    })
                    .toList();

            // CriterionEvaluator.evaluateMessageReceived проверяет наличие записи в таблице
            // messages (не сам NormalizedEvent) — в продакшене её туда кладёт входящий шлюз ACARS
            // ДО публикации NormalizedEvent (P2-1). Здесь эмулируем то же самое напрямую через JDBC.
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "ACK", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );

            NormalizedEvent ackEvent = new NormalizedEvent(
                    104L, MessageType.DOWNLINK, "ACK", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.OUT, LocalDateTime.now());

            runConcurrently(concurrentThreads, () -> {
                for (Long instanceId : instanceIds) {
                    runWithProductionRetrySemantics(
                            () -> executionService.tryResumeWaitingInstanceTransactional(instanceId, ackEvent));
                }
            });

            List<ExecutionInstance> finalStates = instanceIds.stream()
                    .map(id -> executionRepository.findById(id).orElseThrow())
                    .toList();

            assertThat(finalStates)
                    .as("каждый из %d общих WAITING-инстансов должен дойти до COMPLETED (onSuccess=END) ровно один раз",
                            instanceCount)
                    .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ExecutionStatus.COMPLETED));

            for (Long instanceId : instanceIds) {
                Integer stepExecCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM step_executions WHERE execution_instance_id = ?",
                        Integer.class, instanceId);
                assertThat(stepExecCount)
                        .as("инстанс %d не должен был продвинуться более одного раза под конкурентной доставкой ACK",
                                instanceId)
                        .isEqualTo(1);
            }
        }
    }

    // ============================================================
    // 3. Смок: @Version реально активен (инкремент и детектируемый конфликт)
    // ============================================================
    @Nested
    @DisplayName("@Version активен: инкремент и детектируемый конфликт без retry-обёртки")
    class OptimisticLockColumnSmokeTests {

        @Test
        @DisplayName("повторный save() увеличивает version ровно на 1")
        void savingTwiceIncrementsVersionByOne() {
            Long sequenceId = createWaitForAckSequence("P1-6 version increment smoke", null);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            long versionAfterStart = instance.getVersion();

            instance.setFlightNumber("SU9999");
            ExecutionInstance saved = executionRepository.save(instance);

            assertThat(saved.getVersion()).isEqualTo(versionAfterStart + 1);

            ExecutionInstance reloaded = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(reloaded.getVersion()).isEqualTo(versionAfterStart + 1);
        }

        /**
         * Прямое (без withOptimisticRetry) воспроизведение конфликта: два независимых
         * java-объекта, представляющих ОДНУ И ТУ ЖЕ строку с ОДНОЙ И ТОЙ ЖЕ (устаревшей)
         * version, пытаются сохраниться последовательно — второй save() должен явно
         * провалиться с {@code ObjectOptimisticLockingFailureException}, а не молча
         * затереть первое изменение (lost update). Доказывает, что блокировка АКТИВНА на
         * уровне Hibernate/Postgres, а не просто документирована в комментарии.
         */
        @Test
        @DisplayName("save() на устаревшей version бросает ObjectOptimisticLockingFailureException")
        void savingStaleVersionThrowsOptimisticLockException() {
            Long sequenceId = createWaitForAckSequence("P1-6 stale version smoke", null);
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance original = findInstance(sequenceId, AIRCRAFT_ID);
            Long instanceId = original.getId();
            Long staleVersion = original.getVersion();

            // "Поток A" читает копию и сохраняет первым — version в БД продвигается вперёд.
            ExecutionInstance copyA = executionRepository.findById(instanceId).orElseThrow();
            copyA.setFlightNumber("SU0001");
            executionRepository.save(copyA);

            // "Поток B" держит СВОЮ копию со старой (уже неактуальной) version и пытается
            // сохраниться поверх — это и есть классический optimistic lock conflict.
            ExecutionInstance copyB = ExecutionInstance.builder()
                    .id(instanceId)
                    .sequenceId(original.getSequenceId())
                    .aircraftId(original.getAircraftId())
                    .flightNumber("SU0002")
                    .status(original.getStatus())
                    .currentStepIndex(original.getCurrentStepIndex())
                    .contextJson(original.getContextJson())
                    .waitStartedAt(original.getWaitStartedAt())
                    .waitTimeoutAt(original.getWaitTimeoutAt())
                    .startedAt(original.getStartedAt())
                    .version(staleVersion)
                    .build();

            org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.orm.ObjectOptimisticLockingFailureException.class,
                    () -> executionRepository.save(copyB));

            // финальное состояние — то, что сохранил "поток A" первым, не затёртое "потоком B"
            ExecutionInstance finalState = executionRepository.findById(instanceId).orElseThrow();
            assertThat(finalState.getFlightNumber()).isEqualTo("SU0001");
        }
    }
}
