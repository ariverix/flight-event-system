package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-7 (часть 2b, ADR-0002 — docs/adr/ADR-0002-transactional-outbox-vs-direct-call.md):
 * тесты-доказательства идемпотентности + надёжности Transactional Outbox (Spring Modulith
 * Event Publication Registry), требуемые разделом "Спецификация для реализации", п.4.
 *
 * <p>Демо-борт VP-BQR, рейс SU1234 — единый стиль с остальными P1-* сценарными тестами.
 *
 * <p>Покрывает 4 пункта из ТЗ архитектора:
 * <ol>
 *   <li>{@link RepublishOnRestartTests} — незавершённая публикация
 *       ({@code completion_date IS NULL}) реально переигрывается через механизм Spring Modulith
 *       (включённый флагом {@code republish-outstanding-events-on-restart}, см.
 *       application.yml), а не просто лежит в таблице.</li>
 *   <li>{@link DuplicateNormalizedEventStartTests} — повторная доставка ОДНОГО И ТОГО ЖЕ
 *       {@code NormalizedEvent} (тот же {@code messageId}) дважды подряд создаёт РОВНО ОДИН
 *       {@code ExecutionInstance} — ключевой тест, доказывающий закрытие главного пробела
 *       ADR-0002 ({@code ExecutionService.startExecution}).</li>
 *   <li>{@link RepeatedDeliveryOnActiveInstanceTests} — повторная доставка одного события на
 *       WAITING/RUNNING инстанс не производит двойного перехода (формализация P1-6 явным
 *       тестом для контекста P1-7).</li>
 *   <li>{@link OutboxAtomicityTests} — откат бизнес-транзакции не оставляет запись в
 *       {@code event_publication} (атомарность Outbox).</li>
 * </ol>
 */
@DisplayName("P1-7: Transactional Outbox — republish on restart + идемпотентность")
class P1_7_OutboxRepublishAndIdempotencyScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Autowired
    private MessageInputPort messageInputPort;

    @Autowired
    private IncompleteEventPublications incompleteEventPublications;

    @Autowired
    private EventPublicationRegistry eventPublicationRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createSequenceWithoutStartCriteria(String name, List<StepCreateRequest> steps) {
        SequenceCreateRequest createReq =
                new SequenceCreateRequest(name, "P1-7 сценарный тест", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        for (StepCreateRequest step : steps) {
            sequenceUseCase.addStep(created.id(), step, 1L);
        }
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private List<StepCreateRequest> oneActionStepEndOnBoth() {
        return List.of(new StepCreateRequest(
                "Raise condition (единственный шаг)",
                StepType.ACTION,
                "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1-7 marker\",\"alertLevel\":\"LOW\"}",
                null,
                TransitionAction.END, null, false,
                TransitionAction.END, null, false
        ));
    }

    private List<ExecutionInstance> findInstancesForAircraft(String aircraftId) {
        return executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 500))
                .getContent().stream()
                .filter(i -> aircraftId.equals(i.getAircraftId()))
                .toList();
    }

    /**
     * {@code processEvent} — {@code @ApplicationModuleListener} (мета-аннотирован {@code @Async}) —
     * реальный вызов диспетчеризуется в async-исполнитель Spring Modulith, а не выполняется
     * синхронно в вызывающем потоке (см. javadoc {@code P1_6_ConcurrencyAndOptimisticLockingScenarioIntTest}).
     * Ждём появления ожидаемого числа инстансов вместо предположения о синхронности.
     */
    private void awaitInstanceCount(Long sequenceId, String aircraftId, int expectedCount) {
        long deadline = System.currentTimeMillis() + 10_000;
        long lastSeen = -1;
        while (System.currentTimeMillis() < deadline) {
            lastSeen = findInstancesForAircraft(aircraftId).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .count();
            if (lastSeen >= expectedCount) {
                return;
            }
            sleepQuietly(50);
        }
        throw new AssertionError("Expected " + expectedCount + " instances for sequence " + sequenceId
                + " within timeout, last seen count: " + lastSeen);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ============================================================
    // 1. Republish on restart реально переигрывает незавершённую публикацию
    // ============================================================
    @Nested
    @DisplayName("1. Republish on restart переигрывает незавершённую публикацию")
    class RepublishOnRestartTests {

        /**
         * Подтверждает, что флаг {@code spring.modulith.events.republish-outstanding-events-on-restart}
         * (application.yml) реально включён и работает, а не просто присутствует в YAML
         * (ADR-0002, тест-доказательство №1): создаёт реальную незавершённую публикацию
         * {@code NormalizedEvent} напрямую через {@code EventPublicationRegistry.store} (минуя
         * листенер, чтобы строка ГАРАНТИРОВАННО осталась с {@code completion_date IS NULL}), затем
         * вызывает ТОТ ЖЕ механизм переигровки, который Spring Modulith запускает сам через
         * {@code SmartInitializingSingleton.afterSingletonsInstantiated()} on startup —
         * {@code IncompleteEventPublications.resubmitIncompletePublications} — и проверяет, что
         * листенер ({@code ExecutionService.processEvent} → старт-критерий без start-критерия,
         * срабатывающий на {@code FlightStage.INIT}) реально был вызван повторно: новый
         * {@code ExecutionInstance} появляется в БД.
         */
        @Test
        @DisplayName("незавершённая публикация NormalizedEvent переигрывается и реально вызывает listener")
        void incompletePublicationIsResubmittedAndListenerFires() {
            Long sequenceId = createSequenceWithoutStartCriteria(
                    "P1-7 republish demo", oneActionStepEndOnBoth());

            NormalizedEvent event = new NormalizedEvent(
                    500L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now());

            // Запись публикации напрямую в реестр (минуя реальный @ApplicationModuleListener) —
            // эмулирует ТОЧНО ту ситуацию, ради которой включается republish-on-restart: строка
            // event_publication существует с completion_date IS NULL (как если бы процесс упал
            // ПОСЛЕ коммита INSERT, но ДО того, как listener успел подтвердить completion).
            //
            // Формат targetIdentifier — "FQCN.methodName(FQCN параметра)" — подтверждён
            // эмпирически (дамп listener_id из реальной публикации в этом же тестовом контексте):
            // Spring Modulith использует TransactionalApplicationListener#getListenerId(), который
            // для @ApplicationModuleListener-метода без явного id строит идентификатор из
            // полного имени метода и типа параметра-события.
            eventPublicationRegistry.store(event,
                    java.util.stream.Stream.of(PublicationTargetIdentifier.of(
                            "ru.protectinfotrans.eca.execution.application.ExecutionService.processEvent("
                                    + "ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent)")));

            List<TargetEventPublication> incompleteBefore =
                    eventPublicationRegistry.findIncompletePublications().stream()
                            .filter(p -> p.getEvent() instanceof NormalizedEvent ne && ne.messageId() != null
                                    && ne.messageId().equals(500L))
                            .toList();
            assertThat(incompleteBefore)
                    .as("искусственно созданная публикация должна быть видна как незавершённая ДО resubmit")
                    .hasSize(1);

            assertThat(findInstancesForAircraft(AIRCRAFT_ID)).isEmpty();

            // Тот же метод, что Spring Modulith вызывает сам on startup при
            // republish-outstanding-events-on-restart=true (PersistentApplicationEventMulticaster
            // implements IncompleteEventPublications, afterSingletonsInstantiated делегирует сюда).
            Predicate<EventPublication> onlyThisOne = p -> p.getEvent() instanceof NormalizedEvent ne
                    && ne.messageId() != null && ne.messageId().equals(500L);
            incompleteEventPublications.resubmitIncompletePublications(onlyThisOne);

            awaitInstanceCreated(sequenceId, AIRCRAFT_ID);

            List<ExecutionInstance> created = findInstancesForAircraft(AIRCRAFT_ID).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .toList();
            assertThat(created)
                    .as("resubmit должен реально вызвать processEvent → startExecution (без start-критерия, INIT)")
                    .hasSize(1);
            assertThat(created.get(0).getTriggeringMessageId()).isEqualTo(500L);

            // Публикация подтверждена (completion-mode=update) — больше не входит в incomplete.
            List<TargetEventPublication> incompleteAfter =
                    eventPublicationRegistry.findIncompletePublications().stream()
                            .filter(p -> p.getEvent() instanceof NormalizedEvent ne && ne.messageId() != null
                                    && ne.messageId().equals(500L))
                            .toList();
            assertThat(incompleteAfter)
                    .as("после успешной повторной доставки публикация должна быть отмечена завершённой")
                    .isEmpty();
        }

        private void awaitInstanceCreated(Long sequenceId, String aircraftId) {
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                boolean found = findInstancesForAircraft(aircraftId).stream()
                        .anyMatch(i -> i.getSequenceId().equals(sequenceId));
                if (found) {
                    return;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            throw new AssertionError("Instance for sequence " + sequenceId + " was not created within timeout");
        }
    }

    // ============================================================
    // 2. Повторная доставка NormalizedEvent → ровно один ExecutionInstance (ключевой тест)
    // ============================================================
    @Nested
    @DisplayName("2. Повторная доставка NormalizedEvent не создаёт дублирующийся ExecutionInstance")
    class DuplicateNormalizedEventStartTests {

        /**
         * Ключевой приёмочный тест ADR-0002: эмулирует at-least-once повтор (republish-on-restart
         * или retry на транзиентной ошибке) — ОДИН И ТОТ ЖЕ {@code NormalizedEvent} (тот же
         * {@code messageId=501}) обрабатывается {@code processEvent} ДВАЖДЫ, ПОСЛЕДОВАТЕЛЬНО
         * (вторая доставка ждёт, пока первая полностью завершится — именно так выглядит реальный
         * at-least-once повтор: republish-on-restart срабатывает ПОСЛЕ того, как предыдущая
         * попытка обработки публикации завершилась, успешно или с ошибкой, не ПАРАЛЛЕЛЬНО с ней;
         * то же верно для retry на транзиентной ошибке листенера — Spring Modulith не
         * диспетчеризует ОДНУ И ТУ ЖЕ публикацию в два потока одновременно). Без дедуп-проверки в
         * {@code startExecution} (до P1-7) вторая (после завершения первой) доставка создала бы
         * ВТОРОЙ {@code ExecutionInstance} — после P1-7 должен остаться РОВНО один, с заполненным
         * {@code triggeringMessageId}.
         *
         * <p>Намеренно НЕ тестирует гонку "оба вызова в параллельных потоках одновременно видят
         * 'инстанса ещё нет' и оба вставляют" — это другая (нереалистичная для at-least-once
         * Outbox-доставки ОДНОЙ публикации) гипотетическая ситуация параллельного фан-аута ДВУХ
         * РАЗНЫХ публикаций с одним messageId, которая в продакшене не возникает (Outbox хранит
         * и резолвит каждую публикацию последовательно); если в будущем появится такой канал
         * (несколько реплик читают и обрабатывают одну и ту же незавершённую публикацию
         * параллельно), потребуется отдельный механизм (например уникальный индекс/constraint —
         * см. обоснование в отчёте к этой задаче), не предмет данного теста.
         */
        @Test
        @DisplayName("processEvent дважды (последовательно) с тем же messageId → ровно один ExecutionInstance")
        void duplicateDeliveryOfSameNormalizedEventCreatesExactlyOneInstance() {
            Long sequenceId = createSequenceWithoutStartCriteria(
                    "P1-7 dedup demo", oneActionStepEndOnBoth());

            NormalizedEvent event = new NormalizedEvent(
                    501L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now());

            // processEvent — это @ApplicationModuleListener (мета-аннотирован @Async) — реальный
            // вызов диспетчеризуется в async-исполнитель Spring Modulith (см. javadoc P1-6
            // в P1_6_ConcurrencyAndOptimisticLockingScenarioIntTest), а не выполняется синхронно
            // в вызывающем потоке. Первая "доставка" — ждём её полного завершения (см. javadoc
            // метода) перед тем, как эмулировать повторную доставку.
            executionService.processEvent(event);
            awaitInstanceCount(sequenceId, AIRCRAFT_ID, 1);

            executionService.processEvent(event);
            // даём шанс ВТОРОМУ (дублирующему) старту проявиться, если бы дедуп не работал —
            // короткая фиксированная пауза, не замена await выше (await уже подтвердил >=1).
            sleepQuietly(500);

            List<ExecutionInstance> instances = findInstancesForAircraft(AIRCRAFT_ID).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .toList();

            assertThat(instances)
                    .as("повторная (последовательная) доставка ОДНОГО messageId не должна создать второй инстанс")
                    .hasSize(1);
            assertThat(instances.get(0).getTriggeringMessageId()).isEqualTo(501L);
        }

        /**
         * Симметричный тест на полный путь приёма (REST-уровень MessageInputPort, не напрямую
         * NormalizedEvent): два РАЗНЫХ messageId (разные сообщения) на одном борту, оба
         * совпадающие со start-критерием — должны создать ДВА разных инстанса (не ложно
         * задедуплены): дедуп-ключ включает triggeringMessageId, а не просто
         * (sequenceId, aircraftId, flightNumber) — подтверждает, что легитимный повторный старт
         * (другое сообщение) не блокируется ошибочно широким дедупом.
         */
        @Test
        @DisplayName("два РАЗНЫХ messageId на стадии INIT создают ДВА разных инстанса (не ложный дедуп)")
        void differentMessageIdsCreateSeparateInstances() {
            Long sequenceId = createSequenceWithoutStartCriteria(
                    "P1-7 no-false-dedup demo", oneActionStepEndOnBoth());

            NormalizedEvent first = new NormalizedEvent(
                    601L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now());
            NormalizedEvent second = new NormalizedEvent(
                    602L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, "SU5678",
                    FlightStage.INIT, LocalDateTime.now());

            executionService.processEvent(first);
            executionService.processEvent(second);

            awaitInstanceCount(sequenceId, AIRCRAFT_ID, 2);

            List<ExecutionInstance> instances = findInstancesForAircraft(AIRCRAFT_ID).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .toList();

            assertThat(instances)
                    .as("разные messageId/рейсы — это легитимно отдельные старты, не дубликаты")
                    .hasSize(2);
            assertThat(instances).extracting(ExecutionInstance::getTriggeringMessageId)
                    .containsExactlyInAnyOrder(601L, 602L);
        }

        /**
         * Старт НЕ от конкретного сообщения ({@code triggeringMessageId == null}, например смена
         * стадии полёта через {@code notifyFlightStageChange} — см. {@code EventProcessorService})
         * — дедуп по сообщению не применяется (см. javadoc {@code ExecutionService#startExecution}).
         * Подтверждает осознанно принятое поведение, а не случайный пропуск проверки.
         */
        @Test
        @DisplayName("старт без triggeringMessageId (смена стадии) не дедуплицируется по сообщению — оба старта проходят")
        void nullTriggeringMessageIdStartsAreNotDeduped() {
            Long sequenceId = createSequenceWithoutStartCriteria(
                    "P1-7 null-message demo", oneActionStepEndOnBoth());

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.INIT);
            awaitInstanceCount(sequenceId, AIRCRAFT_ID, 1);

            // Сама последовательность уже завершена (END/END после ACTION) — повторное событие
            // INIT с другим рейсом легитимно стартует НОВЫЙ инстанс той же sequence.
            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, "SU0002", FlightStage.INIT);
            awaitInstanceCount(sequenceId, AIRCRAFT_ID, 2);

            List<ExecutionInstance> instances = findInstancesForAircraft(AIRCRAFT_ID).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .toList();
            assertThat(instances).allSatisfy(i -> assertThat(i.getTriggeringMessageId()).isNull());
        }
    }

    // ============================================================
    // 3. Повторная доставка на WAITING/RUNNING инстанс — не двойной переход (P1-6, формализовано для P1-7)
    // ============================================================
    @Nested
    @DisplayName("3. Повторная доставка на активный инстанс не производит двойной переход")
    class RepeatedDeliveryOnActiveInstanceTests {

        private Long createWaitForAckSequence(String name) {
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    name, "P1-7 контекст — формализация P1-6", null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Ждать ACK",
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
            return findInstancesForAircraft(aircraftId).stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No instance found for sequence " + sequenceId));
        }

        /**
         * Та же доставка ОДНОГО ACK-события {@code tryResumeWaitingInstanceTransactional} дважды
         * подряд на ОДИН WAITING-инстанс — формализует естественную идемпотентность P1-6
         * (перечитывание статуса перед действием) явным регрессионным тестом в контексте ADR-0002:
         * второй вызов должен быть no-op, т.к. инстанс уже не WAITING к моменту повторного вызова.
         */
        @Test
        @DisplayName("tryResumeWaitingInstanceTransactional дважды с тем же ACK — ровно один переход в COMPLETED")
        void duplicateAckDeliveryDoesNotDoubleAdvanceWaitingInstance() {
            Long sequenceId = createWaitForAckSequence("P1-7 dup ack on waiting");
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "ACK", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );

            NormalizedEvent ackEvent = new NormalizedEvent(
                    700L, MessageType.DOWNLINK, "ACK", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.OUT, LocalDateTime.now());

            // Первая доставка резолвит WAIT (SUCCESS → END → COMPLETED).
            executionService.tryResumeWaitingInstanceTransactional(instance.getId(), ackEvent);
            ExecutionInstance afterFirst = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterFirst.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            // Повторная доставка ТОГО ЖЕ события (at-least-once redelivery) — должна быть no-op:
            // инстанс уже не WAITING, метод обязан перечитать статус и ничего не делать.
            executionService.tryResumeWaitingInstanceTransactional(instance.getId(), ackEvent);

            ExecutionInstance afterSecond = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterSecond.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(afterSecond.getVersion())
                    .as("повторная доставка не должна вызывать дополнительный save() — version не растёт дальше")
                    .isEqualTo(afterFirst.getVersion());

            Integer stepExecCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM step_executions WHERE execution_instance_id = ?",
                    Integer.class, instance.getId());
            assertThat(stepExecCount)
                    .as("повторная доставка не должна продублировать запись истории шага")
                    .isEqualTo(1);
        }

        /**
         * Симметрично для stop-критерия: {@code checkStopCriterionTransactional} дважды подряд
         * на один RUNNING-инстанс с совпадающим stop-критерием — ABORTED ровно один раз, без
         * второй записи перехода/события.
         */
        @Test
        @DisplayName("checkStopCriterionTransactional дважды с тем же событием — ровно один переход в ABORTED")
        void duplicateStopCriterionDeliveryDoesNotDoubleAbort() {
            String stopCriteria = "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}";
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P1-7 dup stop demo", "формализация P1-6 для контекста P1-7", null, stopCriteria);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Ждать ACK (никогда не придёт)",
                    StepType.WAIT,
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                            + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                    600,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance instance = findInstance(created.id(), AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            NormalizedEvent stageEvent = new NormalizedEvent(
                    701L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now());

            executionService.checkStopCriterionTransactional(instance.getId(), stageEvent);
            ExecutionInstance afterFirst = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(afterFirst.getStatus()).isEqualTo(ExecutionStatus.ABORTED);

            executionService.checkStopCriterionTransactional(instance.getId(), stageEvent);
            ExecutionInstance afterSecond = executionRepository.findById(instance.getId()).orElseThrow();

            assertThat(afterSecond.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(afterSecond.getVersion())
                    .as("повторная доставка не должна вызывать дополнительный save()")
                    .isEqualTo(afterFirst.getVersion());
        }
    }

    // ============================================================
    // 4. Атомарность записи в Outbox — откат транзакции не оставляет запись в event_publication
    // ============================================================
    @Nested
    @DisplayName("4. Атомарность Outbox: откат бизнес-транзакции не оставляет запись в event_publication")
    class OutboxAtomicityTests {

        /**
         * {@code EventProcessorService.receiveMessage} (класс {@code @Transactional}) делает
         * {@code messageRepository.save()} (запись в {@code messages}) и
         * {@code eventPublisher.publish()} (запись в {@code event_publication}, Spring Modulith
         * Event Publication Registry) в ОДНОЙ транзакции. Если транзакция откатывается ПОСЛЕ
         * вызова обоих — Outbox-запись не должна остаться в БД отдельно от бизнес-изменения:
         * это и есть гарантия атомарности "изменение состояния + публикация события", на которой
         * базируется весь ADR-0002.
         *
         * <p>Откат форсируется явным {@code TransactionTemplate} вокруг вызова сервиса —
         * {@code receiveMessage} сам не бросает исключение в штатном пути, поэтому транзакция
         * помечается {@code setRollbackOnly()} вручную ПОСЛЕ вызова (тот же эффект, что и сбой
         * между save() и commit в реальной системе — момент отказа здесь не важен, важно что
         * COMMIT не происходит вообще).
         */
        @Test
        @DisplayName("rollback после save()+publish() не оставляет ни сообщение, ни event_publication")
        void rollbackLeavesNoMessageAndNoEventPublication() {
            long messagesBefore = countMessages();
            long publicationsBefore = countEventPublications();

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                messageInputPort.receiveMessage(
                        MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                        "{}", Map.of());
                // форсируем откат ПОСЛЕ того, как save()+publish() уже выполнены внутри транзакции —
                // эмулирует крах/исключение до commit
                status.setRollbackOnly();
            });

            long messagesAfter = countMessages();
            long publicationsAfter = countEventPublications();

            assertThat(messagesAfter)
                    .as("откат должен откатить INSERT в messages")
                    .isEqualTo(messagesBefore);
            assertThat(publicationsAfter)
                    .as("откат должен откатить INSERT в event_publication — Outbox-запись не " +
                            "переживает откат бизнес-транзакции (атомарность, не fire-and-forget)")
                    .isEqualTo(publicationsBefore);
        }

        /**
         * Контрольный (положительный) случай: без принудительного rollback — обе записи (бизнес +
         * Outbox) реально коммитятся вместе. Доказывает, что предыдущий тест проверяет ИМЕННО
         * атомарность отката, а не отсутствие записи вообще (например из-за неправильной
         * настройки теста).
         */
        @Test
        @DisplayName("без rollback — и сообщение, и event_publication присутствуют после commit")
        void commitLeavesBothMessageAndEventPublication() {
            long messagesBefore = countMessages();
            long publicationsBefore = countEventPublications();

            messageInputPort.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "{}", Map.of());

            long messagesAfter = countMessages();
            long publicationsAfter = countEventPublications();

            assertThat(messagesAfter).isEqualTo(messagesBefore + 1);
            assertThat(publicationsAfter).isGreaterThan(publicationsBefore);
        }

        private long countMessages() {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Long.class);
        }

        private long countEventPublications() {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event_publication", Long.class);
        }
    }
}
