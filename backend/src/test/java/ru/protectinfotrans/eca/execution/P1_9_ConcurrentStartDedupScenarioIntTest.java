package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-9 (V38, закрытие архитектурного backlog P1-7/P6-1): гарантия «ровно один старт инстанса»
 * под ИСТИННОЙ параллельной конкуренцией — то, что явно НЕ покрывал
 * {@link P1_7_OutboxRepublishAndIdempotencyScenarioIntTest} (там доставка последовательна, см. его
 * javadoc: «если появится канал, где несколько реплик обрабатывают одну публикацию параллельно,
 * потребуется уникальный индекс/constraint — не предмет данного теста»). Этот тест — как раз тот
 * предмет: несколько реплик backend (k8s replicas:2 + HPA, P6-1) при повторной доставке одного
 * сообщения могут одновременно пройти дедуп-пред-проверку до коммита любого из них.
 *
 * <p>Гарантия перенесена на уровень БД частичным уникальным индексом
 * {@code idx_exec_dedup_trigger_unique} (V38, {@code NULLS NOT DISTINCT},
 * {@code WHERE triggering_message_id IS NOT NULL}) — тот же принцип DB-level single-fire, что у
 * WAIT-таймаутов P1-5. {@link ExecutionService#startExecutionDeduplicated} ловит проигрыш гонки как
 * идемпотентный no-op.
 *
 * <p>Демо-борт VP-BQR, рейс SU1234.
 */
@DisplayName("P1-9: конкурентный двойной старт — ровно один инстанс (V38 unique index)")
class P1_9_ConcurrentStartDedupScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createSequenceWithoutStartCriteria(String name) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "P1-9 concurrency test", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Raise condition (единственный шаг)",
                StepType.ACTION,
                "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1-9 marker\",\"alertLevel\":\"LOW\"}",
                null,
                TransitionAction.END, null, false,
                TransitionAction.END, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    /**
     * Последовательность с WAIT-шагом первым: старт создаёт инстанс и переводит его в WAITING без
     * внешнего побочного эффекта (в отличие от ACTION RAISE_CONDITION) — чистая проверка именно
     * дедупа старта под конкуренцией, инстанс остаётся в БД для подсчёта.
     */
    private Long createWaitFirstSequence(String name) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "P1-9 concurrency test (wait-first)", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Ждать ACK (никогда не придёт в этом тесте)",
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

    private List<ExecutionInstance> instancesFor(Long sequenceId, String aircraftId) {
        return executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 500))
                .getContent().stream()
                .filter(i -> aircraftId.equals(i.getAircraftId()) && i.getSequenceId().equals(sequenceId))
                .toList();
    }

    /** Минимальный сырой INSERT инстанса в обход дедуп-пред-проверки — прямой тест ограничения БД. */
    private int rawInsert(Long sequenceId, String aircraftId, String flightNumber, Long triggeringMessageId) {
        return jdbcTemplate.update(
                "INSERT INTO execution_instances "
                        + "(sequence_id, aircraft_id, flight_number, status, current_step_index, "
                        + "triggering_message_id, version, started_at, updated_at) "
                        + "VALUES (?, ?, ?, 'RUNNING', 1, ?, 0, NOW(), NOW())",
                sequenceId, aircraftId, flightNumber, triggeringMessageId);
    }

    // ============================================================
    // 1. Ограничение БД (детерминированно, без тайминга)
    // ============================================================
    @Nested
    @DisplayName("1. Уникальный индекс V38 — ограничение уровня БД")
    class DatabaseConstraintTests {

        @Test
        @DisplayName("сырой дубль с тем же (sequence, aircraft, flight, triggeringMessageId) отвергается БД")
        void rawDuplicateWithSameKeyIsRejected() {
            Long sequenceId = createSequenceWithoutStartCriteria("P1-9 raw dup");

            assertThat(rawInsert(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER, 900L)).isEqualTo(1);

            assertThatThrownBy(() -> rawInsert(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER, 900L))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(instancesFor(sequenceId, AIRCRAFT_ID)).hasSize(1);
        }

        @Test
        @DisplayName("частичный WHERE: два старта с triggeringMessageId=NULL НЕ ограничиваются (не дедупятся по сообщению)")
        void nullTriggeringMessageIdDuplicatesAreAllowed() {
            Long sequenceId = createSequenceWithoutStartCriteria("P1-9 null trigger");

            assertThat(rawInsert(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER, null)).isEqualTo(1);
            // второй с NULL triggering_message_id проходит — индекс частичный (WHERE ... IS NOT NULL),
            // такие старты (смена стадии/ручной) осознанно не дедупятся по сообщению.
            assertThat(rawInsert(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER, null)).isEqualTo(1);

            assertThat(instancesFor(sequenceId, AIRCRAFT_ID)).hasSize(2);
        }

        @Test
        @DisplayName("NULLS NOT DISTINCT: дубль с NULL flight_number, но тем же triggeringMessageId — отвергается")
        void nullFlightNumberStillDeduplicatedWhenTriggeringMessageIdEqual() {
            Long sequenceId = createSequenceWithoutStartCriteria("P1-9 null flight");

            assertThat(rawInsert(sequenceId, AIRCRAFT_ID, null, 901L)).isEqualTo(1);

            assertThatThrownBy(() -> rawInsert(sequenceId, AIRCRAFT_ID, null, 901L))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(instancesFor(sequenceId, AIRCRAFT_ID)).hasSize(1);
        }
    }

    // ============================================================
    // 2. Сквозной путь processEvent под параллельной конкуренцией
    // ============================================================
    @Nested
    @DisplayName("2. Параллельная доставка одного события — ровно один инстанс, без исключений наружу")
    class ConcurrentProcessEventTests {

        /**
         * Ключевой тест: N потоков ОДНОВРЕМЕННО (CyclicBarrier) обрабатывают ОДИН И ТОТ ЖЕ
         * {@code NormalizedEvent} (тот же messageId) — эмуляция повторной доставки одного сообщения
         * на несколько реплик backend. В отличие от P1-7 (последовательная доставка), здесь потоки
         * реально стартуют одновременно и могут вместе пройти дедуп-пред-проверку до коммита любого.
         *
         * <p>{@code processEvent} — {@code @ApplicationModuleListener} ({@code @Async}) —
         * диспетчеризуется в async-исполнитель (потоки {@code task-N}), поэтому вызов возвращается
         * ДО завершения обработки: после старта потоков ждём стабилизации (poll до появления инстанса
         * + контрольная пауза, что второй не появился). Инвариант: ровно один {@link ExecutionInstance}
         * (уникальный индекс V38 не даёт создать второй, проигрыш гонки — идемпотентный no-op внутри
         * {@code startExecutionDeduplicated}, наружу исключение не проходит — иначе async-фреймворк
         * залогировал бы ERROR, но инстанс всё равно был бы один).
         */
        @Test
        @DisplayName("8 потоков с тем же messageId → ровно один инстанс")
        void concurrentDuplicateStartsCreateExactlyOneInstance() throws Exception {
            Long sequenceId = createWaitFirstSequence("P1-9 concurrent start");

            NormalizedEvent event = new NormalizedEvent(
                    902L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now());

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            try {
                List<Future<?>> submitted = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    submitted.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        executionService.processEvent(event);
                        return null;
                    }));
                }
                for (Future<?> f : submitted) {
                    f.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }

            // async-обработка завершается на потоках task-N уже после возврата processEvent — ждём,
            // пока ровно один инстанс появится, затем подтверждаем, что второй не возникает.
            awaitInstanceCount(sequenceId, 1);
            sleepQuietly(750);

            List<ExecutionInstance> instances = instancesFor(sequenceId, AIRCRAFT_ID);
            assertThat(instances)
                    .as("конкурентная доставка ОДНОГО messageId должна создать РОВНО один инстанс")
                    .hasSize(1);
            assertThat(instances.get(0).getTriggeringMessageId()).isEqualTo(902L);
        }

        private void awaitInstanceCount(Long sequenceId, int expected) {
            long deadline = System.currentTimeMillis() + 10_000;
            long seen = -1;
            while (System.currentTimeMillis() < deadline) {
                seen = instancesFor(sequenceId, AIRCRAFT_ID).size();
                if (seen >= expected) {
                    return;
                }
                sleepQuietly(50);
            }
            throw new AssertionError("Ожидали " + expected + " инстанс(ов) в пределах таймаута, видно: " + seen);
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }
}
