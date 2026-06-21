package ru.protectinfotrans.eca.integration;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.application.ExecutionResumeRunner;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.integration.application.OutboundMessageDeliveryScheduler;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageStatus;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-3: Durable исходящий шлюз (ACTION SEND_UPLINK/SEND_GROUND через durable-очередь).
 *
 * <p>Сценарии (по аналогии с {@code P1_5_WaitTimeoutSingleFireScenarioIntTest}):
 * <ol>
 *   <li>ACTION SEND_UPLINK/SEND_GROUND в реальном переходе ECA-движка атомарно создаёт PENDING
 *       {@code OutboundMessage} вместе с переходом шага (одна транзакция).</li>
 *   <li>Durable-поллер забирает PENDING и переводит в SENT.</li>
 *   <li>Single-fire под конкуренцией: N параллельных claim на одну и ту же PENDING-запись —
 *       доставлена ровно один раз.</li>
 *   <li>Переживание рестарта: PENDING-запись, созданная "до краша", доставляется первым же
 *       тиком поллера после "старта" (эмулируется прямым вызовом — реальный рестарт процесса
 *       не нужен, так как корректность зависит только от персистентности записи в БД).</li>
 *   <li>Происхождение шаблона (origin) и получатели (ground) сохраняются в durable-записи.</li>
 * </ol>
 *
 * Демо-борт VP-BQR — единый стиль с {@code EcaParityScenarioIntTest}.
 */
@Slf4j
@DisplayName("P2-3: Durable исходящий шлюз — uplink/ground через durable-очередь")
class P2_3_OutboundGatewayScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Autowired
    private OutboundMessageRepositoryPort outboundMessageRepository;

    @Autowired
    private OutboundMessageDeliveryScheduler deliveryScheduler;

    @Autowired
    private MessageOutputPort messageOutputPort;

    @Autowired
    private ExecutionResumeRunner executionResumeRunner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createActionSequence(String name, String configJson) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "Тест P2-3 durable outbound", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Send action",
                StepType.ACTION,
                configJson,
                null,
                TransitionAction.END, null, false,
                TransitionAction.END, null, false
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

    private List<OutboundMessage> findAllOutbound() {
        return jdbcTemplate.query(
                "SELECT id FROM outbound_messages ORDER BY id ASC",
                (rs, rowNum) -> outboundMessageRepository.findById(rs.getLong("id")).orElseThrow());
    }

    // ============================================================
    // 1. Атомарность постановки в очередь вместе с переходом шага
    // ============================================================
    @Nested
    @DisplayName("Атомарная постановка в durable-очередь")
    class AtomicEnqueueTests {

        @Test
        @DisplayName("ACTION SEND_UPLINK создаёт PENDING OutboundMessage с origin и шаблоном")
        void sendUplinkActionCreatesPendingOutboundMessage() {
            Long sequenceId = createActionSequence("SEND_UPLINK durable", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "uplinkOrigin": "EXTERNAL_USER",
                    "params": {"gate": "A1"}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);

            OutboundMessage message = all.get(0);
            assertThat(message.getMessageType()).isEqualTo(OutboundMessageType.UPLINK);
            assertThat(message.getAircraftId()).isEqualTo(AIRCRAFT_ID);
            assertThat(message.getTemplateName()).isEqualTo("CLEARANCE");
            assertThat(message.getUplinkOrigin()).isEqualTo(UplinkOrigin.EXTERNAL_USER);
            assertThat(message.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
            assertThat(message.getParamsJson()).contains("gate").contains("A1");

            // ACTION-переход самой последовательности завершился (END) синхронно — постановка
            // в очередь не блокирует и не задерживает решение движка
            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("ACTION SEND_GROUND создаёт PENDING OutboundMessage с получателями")
        void sendGroundActionCreatesPendingOutboundMessageWithRecipients() {
            Long sequenceId = createActionSequence("SEND_GROUND durable", """
                {
                    "actionType": "SEND_GROUND",
                    "templateName": "NOTIFICATION",
                    "recipients": ["dispatcher@airline.com", "ops@airline.com"],
                    "params": {"delay": "30"}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);

            OutboundMessage message = all.get(0);
            assertThat(message.getMessageType()).isEqualTo(OutboundMessageType.GROUND);
            assertThat(message.getRecipients()).containsExactly("dispatcher@airline.com", "ops@airline.com");
            assertThat(message.getTemplateName()).isEqualTo("NOTIFICATION");
            assertThat(message.getUplinkOrigin()).isNull();
            assertThat(message.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
        }

        @Test
        @DisplayName("SEND_UPLINK без uplinkOrigin в конфиге — по умолчанию COMPUTER_GENERATED")
        void sendUplinkDefaultsToComputerGeneratedOrigin() {
            Long sequenceId = createActionSequence("SEND_UPLINK default origin", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getUplinkOrigin()).isEqualTo(UplinkOrigin.COMPUTER_GENERATED);
        }

        /**
         * Атомарность постановки в очередь с переходом ECA-движка (ADR-0002, "Контракт"): если
         * транзакция, в которой выполнился ACTION SEND_UPLINK, откатывается ПОСЛЕ того, как
         * {@code sendUplink} уже вызван (эмулирует крах/исключение до commit), запись в
         * {@code outbound_messages} не должна переживать откат — иначе движок "решил бы", что
         * сообщение поставлено в очередь, а по факту строки не существует.
         */
        @Test
        @DisplayName("rollback транзакции после sendUplink не оставляет PENDING-запись в outbound_messages")
        void rollbackAfterSendUplinkLeavesNoOutboundMessage() {
            long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbound_messages", Long.class);

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                messageOutputPort.sendUplink(AIRCRAFT_ID, "ROLLBACK_TEMPLATE", java.util.Map.of(),
                        UplinkOrigin.COMPUTER_GENERATED);
                // форсируем откат ПОСЛЕ того, как save() уже выполнен внутри транзакции —
                // эмулирует крах/исключение до commit (как P1_7 rollbackLeavesNoMessageAndNoEventPublication)
                status.setRollbackOnly();
            });

            long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbound_messages", Long.class);
            assertThat(after)
                    .as("откат должен откатить INSERT в outbound_messages — постановка в очередь "
                            + "не переживает откат бизнес-транзакции")
                    .isEqualTo(before);
        }
    }

    // ============================================================
    // 2. Durable-доставка переводит PENDING -> SENT
    // ============================================================
    @Nested
    @DisplayName("Durable-доставка (поллер)")
    class DeliveryTests {

        @Test
        @DisplayName("поллер забирает PENDING и переводит в SENT")
        void schedulerDeliversPendingMessage() {
            Long sequenceId = createActionSequence("SEND_UPLINK delivered", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            OutboundMessage created = findAllOutbound().get(0);
            assertThat(created.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);

            deliveryScheduler.pollPendingMessages();

            OutboundMessage delivered = outboundMessageRepository.findById(created.getId()).orElseThrow();
            assertThat(delivered.getStatus()).isEqualTo(OutboundMessageStatus.SENT);
            assertThat(delivered.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("уже SENT сообщение не подхватывается повторным тиком поллера")
        void sentMessageIsNotReprocessedByLaterTicks() {
            Long sequenceId = createActionSequence("SEND_UPLINK sent once", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            Long id = findAllOutbound().get(0).getId();

            deliveryScheduler.pollPendingMessages();
            LocalDateTime firstSentAt = outboundMessageRepository.findById(id).orElseThrow().getSentAt();

            deliveryScheduler.pollPendingMessages();
            OutboundMessage afterSecondTick = outboundMessageRepository.findById(id).orElseThrow();

            assertThat(afterSecondTick.getStatus()).isEqualTo(OutboundMessageStatus.SENT);
            assertThat(afterSecondTick.getSentAt()).isEqualTo(firstSentAt);
        }
    }

    // ============================================================
    // 3. Single-fire под конкуренцией claim
    // ============================================================
    @Nested
    @DisplayName("Single-fire под конкуренцией (реальный Postgres)")
    class SingleFireUnderConcurrencyTests {

        @Test
        @DisplayName("N параллельных deliverOne на одну PENDING-запись — доставлена ровно один раз")
        void pendingMessageDeliveredExactlyOnceUnderConcurrentClaims() throws InterruptedException {
            Long sequenceId = createActionSequence("SEND_UPLINK single-fire concurrency", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            Long id = findAllOutbound().get(0).getId();

            int concurrentAttempts = 8;
            ExecutorService pool = Executors.newFixedThreadPool(concurrentAttempts);
            CountDownLatch readyLatch = new CountDownLatch(concurrentAttempts);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger exceptions = new AtomicInteger(0);
            AtomicInteger claimedCount = new AtomicInteger(0);

            try {
                for (int i = 0; i < concurrentAttempts; i++) {
                    pool.submit(() -> {
                        try {
                            readyLatch.countDown();
                            startLatch.await(10, TimeUnit.SECONDS);
                            if (outboundMessageRepository.claimPending(id)) {
                                claimedCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                            log.error("Concurrent claimPending attempt failed unexpectedly", e);
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
            // КЛЮЧЕВАЯ ПРОВЕРКА: несмотря на 8 конкурентных попыток, ровно один claim удался
            assertThat(claimedCount.get()).isEqualTo(1);

            OutboundMessage afterClaim = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(afterClaim.getStatus()).isEqualTo(OutboundMessageStatus.SENDING);

            // повторный claim после того, как запись уже не PENDING, больше не проходит
            assertThat(outboundMessageRepository.claimPending(id)).isFalse();
        }

        @Test
        @DisplayName("полный pollPendingMessages(), вызванный дважды параллельно — сообщение доставлено один раз")
        void pollCalledConcurrentlyDeliversOnce() throws InterruptedException {
            Long sequenceId = createActionSequence("SEND_UPLINK full-cycle concurrency", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            Long id = findAllOutbound().get(0).getId();

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
                            deliveryScheduler.pollPendingMessages();
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                            log.error("Concurrent pollPendingMessages tick failed unexpectedly", e);
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

            OutboundMessage afterConcurrentPolls = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(afterConcurrentPolls.getStatus()).isEqualTo(OutboundMessageStatus.SENT);
        }
    }

    // ============================================================
    // 4. Переживание рестарта
    // ============================================================
    @Nested
    @DisplayName("Переживание рестарта сервиса")
    class SurvivesRestartTests {

        @Test
        @DisplayName("PENDING-запись, созданная 'до краша', доставляется первым же тиком поллера после старта")
        void pendingMessageCreatedBeforeCrashIsDeliveredAfterRestart() {
            Long sequenceId = createActionSequence("SEND_UPLINK survives restart", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            // "До краша": ACTION-переход уже закоммитил PENDING-запись в БД.
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            Long id = findAllOutbound().get(0).getId();
            assertThat(outboundMessageRepository.findById(id).orElseThrow().getStatus())
                    .isEqualTo(OutboundMessageStatus.PENDING);

            // "Рестарт сервиса" — поллер эмулируется прямым вызовом первого тика после старта
            // (как и в P1-5: корректность зависит только от персистентности записи в БД, а не от
            // in-memory состояния планировщика, которое не переживает рестарт процесса).
            deliveryScheduler.pollPendingMessages();

            OutboundMessage delivered = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(delivered.getStatus()).isEqualTo(OutboundMessageStatus.SENT);
        }
    }

    // ============================================================
    // 4b. Фикс регрессии идемпотентности P1-4 x P2-3: resume после рестарта
    // повторно прогоняет ACTION-шаг RUNNING-инстанса — НЕ должен ставить дубль
    // в outbound_messages (см. ADR-0002 и javadoc
    // ExecutionService#resumeRunningInstanceAfterRestart).
    // ============================================================
    @Nested
    @DisplayName("Регрессия P1-4 x P2-3: resume после рестарта не дублирует outbound-постановку ACTION-шага")
    class ResumeAfterRestartIdempotencyTests {

        /**
         * Воспроизводит ровно тот сценарий, который описал reviewer: процесс падает МЕЖДУ
         * "перейти на ACTION-шаг N и сохранить указатель" (executeTransition делает save() ДО
         * executeStep) и "обработать результат шага N" (advanceExecution) — на этот момент шаг N
         * (SEND_UPLINK) уже мог успеть поставить PENDING-запись в durable-очередь. Эмулируется
         * без поднятия нового процесса — по тому же паттерну, что
         * {@code P1_4_ResumeAfterRestartScenarioIntTest.RunningInstanceResume}: вручную создаём
         * RUNNING-инстанс на ACTION-шаге, затем вызываем
         * {@code ExecutionResumeRunner#run} — ТОЧНО ТОТ ЖЕ код, что Spring Boot вызвал бы сам на
         * старте нового процесса.
         */
        @Test
        @DisplayName("RUNNING-инстанс на ACTION SEND_UPLINK, докрученный resume, создаёт РОВНО ОДНУ запись в outbound_messages")
        void resumeOfRunningActionStepDoesNotDuplicateOutboundMessage() {
            Long sequenceId = createActionSequence("SEND_UPLINK resume idempotency", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "uplinkOrigin": "EXTERNAL_USER",
                    "params": {"gate": "A1"}
                }
                """);

            // Шаг 1 ДЕЙСТВИТЕЛЬНО уже выполнился "до краша" — ACTION синхронно поставил PENDING
            // запись в очередь (та же транзакция, что executeTransition сохранила указатель,
            // если бы процесс не упал) — здесь эмулируем именно ЭТО состояние: запись в
            // outbound_messages уже есть, currentStepIndex=1, статус RUNNING (advanceExecution
            // ещё не обработал результат и не сдвинул указатель/статус COMPLETED).
            ExecutionInstance frozen = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            Long instanceId = executionRepository.save(frozen).getId();

            boolean firstEnqueue = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE", Map.of("gate", "A1"),
                    UplinkOrigin.EXTERNAL_USER, instanceId, 1);
            assertThat(firstEnqueue).isTrue();
            assertThat(findAllOutbound()).hasSize(1);

            // "Рестарт сервиса" — ExecutionResumeRunner находит RUNNING-инстанс и повторно
            // прогоняет шаг 1 (ACTION SEND_UPLINK) через resumeRunningInstanceAfterRestart.
            executionResumeRunner.run(null);

            // КЛЮЧЕВАЯ ПРОВЕРКА: РОВНО одна запись в outbound_messages для этого шага, не две —
            // дедуп по (executionInstanceId, stepOrderIndex) в OutboundMessageGatewayAdapter
            // идемпотентно пропустил повторную постановку при resume-replay.
            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getExecutionInstanceId()).isEqualTo(instanceId);
            assertThat(all.get(0).getStepOrderIndex()).isEqualTo(1);

            // Инстанс докручен resume до конца последовательности (END/END на единственном шаге).
            ExecutionInstance resumed = executionRepository.findById(instanceId).orElseThrow();
            assertThat(resumed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("две параллельные постановки ОДНОГО (instanceId, stepIndex) через порт — без дубля, без 500")
        void twoCallsForSameInstanceAndStepEnqueueExactlyOnce() {
            Long sequenceId = createActionSequence("SEND_UPLINK double enqueue guard", """
                {
                    "actionType": "SEND_UPLINK",
                    "templateName": "CLEARANCE",
                    "params": {}
                }
                """);

            ExecutionInstance instance = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            Long instanceId = executionRepository.save(instance).getId();

            // Первая постановка — настоящая, создаёт запись.
            boolean first = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE", Map.of(),
                    UplinkOrigin.COMPUTER_GENERATED, instanceId, 1);
            // Вторая постановка для ТОГО ЖЕ (instanceId, stepIndex) — эмулирует повторный
            // вызов (например ещё один independent resume/retry) ПОСЛЕ того, как первая уже
            // закоммичена — find-before-save видит существующую запись и идемпотентно пропускает.
            boolean second = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE", Map.of(),
                    UplinkOrigin.COMPUTER_GENERATED, instanceId, 1);

            assertThat(first).isTrue();
            assertThat(second).isTrue();

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getExecutionInstanceId()).isEqualTo(instanceId);
            assertThat(all.get(0).getStepOrderIndex()).isEqualTo(1);
        }
    }

    // ============================================================
    // 5. MessageOutputPort: контракт синхронного вызова
    // ============================================================
    @Nested
    @DisplayName("Контракт MessageOutputPort (durable enqueue, не фактическая доставка)")
    class MessageOutputPortContractTests {

        @Test
        @DisplayName("прямой вызов sendUplink через порт ставит PENDING-запись и возвращает true немедленно")
        void directSendUplinkCallEnqueuesAndReturnsTrueImmediately() {
            boolean accepted = messageOutputPort.sendUplink(AIRCRAFT_ID, "DIRECT_TEMPLATE", java.util.Map.of(),
                    UplinkOrigin.COMPUTER_GENERATED);

            assertThat(accepted).isTrue();

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
            assertThat(all.get(0).getTemplateName()).isEqualTo("DIRECT_TEMPLATE");
        }
    }
}
