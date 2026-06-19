package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.application.ExecutionResumeRunner;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4: "Resume после рестарта" — приёмочный сценарный тест.
 *
 * <p>Эмуляция «стоп сервиса с активными инстансами → старт → продолжили» реализована БЕЗ
 * поднятия нового JVM/Spring-контекста (дорого и не даёт лучшей гарантии, чем прямой вызов):
 * инстанс доводится до состояния, которое реально лежит в БД на момент гипотетического краша,
 * затем явно вызывается {@link ExecutionResumeRunner#run}, который выполняет ТОЧНО ТОТ ЖЕ код,
 * что Spring Boot вызвал бы сам через {@code ApplicationRunner} на старте нового процесса —
 * он сам читает состояние заново из репозитория, не из локальной java-переменной теста.
 *
 * <p>Демосценарий: борт VP-BQR, рейс SU1234 (см. CLAUDE.md).
 */
@DisplayName("P1-4: Resume после рестарта сервиса")
class P1_4_ResumeAfterRestartScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Autowired
    private ExecutionResumeRunner executionResumeRunner;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long sequenceId;

    /**
     * 3-шаговая последовательность, идентичная по семантике P1-3 demo:
     *  1. WAIT: POSITION_REPORT (downlink), fromThisPointOnly=true, timeout 60 сек. CONTINUE/CONTINUE.
     *  2. EVALUATE: тот же критерий fromThisPointOnly=true — наследует точку отсчёта шага 1. CONTINUE/CONTINUE.
     *  3. ACTION: RAISE_CONDITION — финальный маркер. END/END.
     */
    @BeforeEach
    void createSequence() {
        SequenceCreateRequest createReq = new SequenceCreateRequest(
                "P1-4 Resume Demo",
                "WAIT(fromThisPointOnly) -> EVALUATE(fromThisPointOnly) -> ACTION, восстанавливается после рестарта",
                null,
                null
        );
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceId = created.id();

        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Wait Position Report",
                StepType.WAIT,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"POSITION_REPORT\",\"fromThisPointOnly\":true}",
                60,
                TransitionAction.CONTINUE,
                null, false,
                TransitionAction.CONTINUE,
                null, false
        ), 1L);

        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Evaluate Position Again",
                StepType.EVALUATE,
                "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                        + "\"templateName\":\"POSITION_REPORT\",\"fromThisPointOnly\":true}",
                null,
                TransitionAction.CONTINUE,
                null, false,
                TransitionAction.CONTINUE,
                null, false
        ), 1L);

        sequenceUseCase.addStep(sequenceId, new StepCreateRequest(
                "Raise marker condition",
                StepType.ACTION,
                "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"P1_4_DEMO\",\"alertLevel\":\"LOW\"}",
                null,
                TransitionAction.END,
                null, false,
                TransitionAction.END,
                null, false
        ), 1L);

        sequenceUseCase.activateSequence(sequenceId, 1L);
    }

    @Nested
    @DisplayName("WAITING-инстанс переживает рестарт")
    class WaitingInstanceResume {

        @Test
        @DisplayName("остаётся на том же шаге/статусе после resume, затем продвигается новым событием")
        void waitingInstanceSurvivesRestartAndThenAdvances() {
            // "До рестарта": инстанс запущен, встал в WAITING на шаге 1
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance beforeRestart = findInstance();
            assertThat(beforeRestart.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(beforeRestart.getCurrentStepIndex()).isEqualTo(1);
            assertThat(beforeRestart.getWaitStartedAt()).isNotNull();
            assertThat(beforeRestart.getWaitTimeoutAt()).isNotNull();
            LocalDateTime waitStartedBeforeRestart = beforeRestart.getWaitStartedAt();

            // "Рестарт сервиса": вызываем ровно тот код, который Spring Boot вызывает сам
            // на старте нового процесса через ApplicationRunner.
            executionResumeRunner.run(null);

            // Инстанс на ТОМ ЖЕ шаге, в ТОМ ЖЕ статусе — resume его не сдвинул и не сбросил окно ожидания
            ExecutionInstance afterResume = findInstance();
            assertThat(afterResume.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(afterResume.getCurrentStepIndex()).isEqualTo(1);
            assertThat(afterResume.getWaitStartedAt()).isEqualTo(waitStartedBeforeRestart);
            assertThat(afterResume.getWaitTimeoutAt()).isEqualTo(beforeRestart.getWaitTimeoutAt());

            // Новое событие после resume должно продвинуть инстанс как если бы рестарта не было
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                    "received_at, position_source, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );
            triggerProcessEvent();

            ExecutionInstance completed = awaitInstance(i -> i.getStatus() == ExecutionStatus.COMPLETED);
            assertThat(completed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(completed.getCurrentStepIndex()).isEqualTo(3);

            List<Integer> stepIndexes = jdbcTemplate.queryForList(
                    "SELECT step_index FROM step_executions WHERE execution_instance_id = ? ORDER BY executed_at ASC",
                    Integer.class, completed.getId());
            assertThat(stepIndexes).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("from-this-point-only: после resume историческое сообщение НЕ закрывает критерий, новое — закрывает")
        void fromThisPointOnlyHonoredAfterResume() {
            // Сообщение от "прошлого рейса" уже в БД ДО старта инстанса
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                    "received_at, position_source, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '1 hour', ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);
            ExecutionInstance beforeRestart = findInstance();
            assertThat(beforeRestart.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // "Рестарт"
            executionResumeRunner.run(null);

            // Историческое сообщение (час назад, ДО точки отсчёта WAIT) не должно закрыть критерий
            // даже после resume — fromThisPointOnly reference восстанавливается из той же строки БД.
            triggerProcessEvent();
            ExecutionInstance stillWaiting = findInstance();
            assertThat(stillWaiting.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(stillWaiting.getCurrentStepIndex()).isEqualTo(1);

            // Новое сообщение ПОСЛЕ точки отсчёта — должно закрыть критерий и продвинуть инстанс
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, " +
                    "received_at, position_source, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );
            triggerProcessEvent();

            ExecutionInstance completed = awaitInstance(i -> i.getStatus() == ExecutionStatus.COMPLETED);
            assertThat(completed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(completed.getCurrentStepIndex()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("RUNNING-инстанс переживает рестарт")
    class RunningInstanceResume {

        @Test
        @DisplayName("инстанс, 'застрявший' в RUNNING на шаге, докручивается до конца последовательности при resume")
        void runningInstanceFrozenMidStepIsReplayedToCompletion() {
            // Эмулируем краш "между save() указателя и обработкой результата шага": вручную создаём
            // RUNNING-инстанс на шаге 3 (ACTION, END/END) — ровно то состояние, которое было бы в БД,
            // если бы процесс упал сразу после executeTransition сохранил currentStepIndex=3,
            // но до того, как advanceExecution обработал бы результат ACTION-шага.
            ExecutionInstance frozen = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(3)
                    .build();
            ExecutionInstance saved = executionRepository.save(frozen);

            // "Рестарт сервиса"
            executionResumeRunner.run(null);

            // Resume повторно выполнил шаг 3 (RAISE_CONDITION, мгновенный ACTION) и докрутил END -> COMPLETED
            ExecutionInstance resumed = executionRepository.findById(saved.getId()).orElseThrow();
            assertThat(resumed.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(resumed.getCurrentStepIndex()).isEqualTo(3);

            List<Integer> stepIndexes = jdbcTemplate.queryForList(
                    "SELECT step_index FROM step_executions WHERE execution_instance_id = ? ORDER BY executed_at ASC",
                    Integer.class, saved.getId());
            assertThat(stepIndexes).containsExactly(3);
        }

        @Test
        @DisplayName("инстанс RUNNING на WAIT-шаге переходит в WAITING при resume, не теряется и не зависает")
        void runningInstanceOnWaitStepBecomesWaitingAfterResume() {
            // RUNNING на шаге 1 (WAIT) — как если бы краш случился до первого прогона WaitStepRule
            // (например прямо на старте инстанса, до того как executeStep успел отработать).
            ExecutionInstance frozen = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            ExecutionInstance saved = executionRepository.save(frozen);

            executionResumeRunner.run(null);

            ExecutionInstance resumed = executionRepository.findById(saved.getId()).orElseThrow();
            assertThat(resumed.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(resumed.getCurrentStepIndex()).isEqualTo(1);
            assertThat(resumed.getWaitTimeoutAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Транзакционная изоляция resume-цикла (фикс ревью)")
    class TransactionalIsolationOfResumeLoop {

        /**
         * Воспроизводит ровно тот баг, который описал reviewer: если resume всех RUNNING-инстансов
         * идёт в ОДНОЙ общей транзакции/Hibernate-сессии, а обработка одного из них реально
         * проваливается на уровне persistence (flush бросает реальное исключение БД, не просто
         * бизнес-исключение), то EntityManager остаётся в невалидном состоянии и соседние
         * инстансы в ТОЙ ЖЕ транзакции либо не восстанавливаются вовсе, либо сами падают с тем
         * же исключением при попытке flush.
         *
         * <p>"Отравленный" инстанс получает PostgreSQL BEFORE UPDATE триггер, который бросает
         * реальную ошибку БД (RAISE EXCEPTION → {@code PSQLException} →
         * {@code DataIntegrityViolationException} на Hibernate flush) ИМЕННО при попытке обновить
         * эту конкретную строку — ровно то, что происходит, когда {@code resumeRunningInstanceAfterRestart}
         * прогоняет WAIT-шаг (результат null) и затем вызывает {@code executionRepository.save(instance)},
         * пытаясь записать снапшот обратно. INSERT при подготовке сценария не затрагивается —
         * триггер навешан только на UPDATE, поэтому "отравлен" специально и только resume-update,
         * а не сам факт существования строки. Это РЕАЛЬНАЯ ошибка persistence-уровня (срабатывает
         * в БД), а не doThrow на моке.
         *
         * <p>На старой реализации (общая {@code @Transactional} на {@code ExecutionResumeRunner#run})
         * этот тест падает: либо здоровые инстансы не переходят в WAITING (общая транзакция
         * откатывается целиком/виснет на испорченной сессии после первого flush-сбоя), либо весь
         * {@code run()} бросает наружу исключение вместо того, чтобы аккуратно залогировать и
         * продолжить со следующим инстансом. После фикса (REQUIRES_NEW на
         * {@code resumeRunningInstanceAfterRestart}, без общей транзакции в {@code run()}) каждый
         * инстанс коммитится/роллбэкается независимо — сбой "отравленного" инстанса откатывает
         * только его собственную транзакцию и не достижим из транзакций соседних.
         */
        @Test
        @DisplayName("сбой ОДНОГО инстанса на уровне persistence не мешает resume ОСТАЛЬНЫХ активных инстансов")
        void persistenceLevelFailureOfOneInstanceDoesNotBreakResumeOfOthers() {
            // Два здоровых RUNNING-инстанса на WAIT-шаге (шаг 1) разных бортов — обычное,
            // валидное состояние "застрял между save() указателя и обработкой результата".
            ExecutionInstance healthyBefore = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            Long healthyBeforeId = executionRepository.save(healthyBefore).getId();

            // "Отравленный" инстанс — обычная валидная строка, но с PostgreSQL-триггером,
            // который реально проваливает любой UPDATE этой строки (см. javadoc выше). Resume
            // встретит её на WAIT-шаге (результат null) и попытается сохранить снапшот —
            // именно этот save() и наткнётся на ошибку БД при flush.
            ExecutionInstance poisoned = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId("VP-POISONED")
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            Long poisonedId = executionRepository.save(poisoned).getId();

            jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_update_on_poisoned_instance()
                RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated persistence failure for poisoned instance %', OLD.id;
                END;
                $$ LANGUAGE plpgsql
                """);
            jdbcTemplate.execute(String.format("""
                CREATE TRIGGER trg_fail_poisoned_instance_update
                BEFORE UPDATE ON execution_instances
                FOR EACH ROW
                WHEN (OLD.id = %d)
                EXECUTE FUNCTION fail_update_on_poisoned_instance()
                """, poisonedId));

            ExecutionInstance healthyAfter = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId("VP-TEST")
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING)
                    .currentStepIndex(1)
                    .build();
            Long healthyAfterId = executionRepository.save(healthyAfter).getId();

            // Подтверждаем стартовое состояние: все три RUNNING и видимы для resume.
            assertThat(executionRepository.findAllActive())
                    .extracting(ExecutionInstance::getId)
                    .containsExactlyInAnyOrder(healthyBeforeId, poisonedId, healthyAfterId);

            // "Рестарт сервиса" — не должен ни упасть, ни оставить здоровые инстансы непродвинутыми.
            executionResumeRunner.run(null);

            // Здоровые инстансы ДО и ПОСЛЕ отравленного в списке активных — оба реально резюмированы
            // (WAIT-шаг повторно прогнан, инстанс перешёл в WAITING с выставленным таймаутом).
            // Это именно то, что общая транзакция могла сломать: на старой реализации после сбоя
            // на poisonedId либо EntityManager уже невалиден для healthyAfter (тот же цикл, та же
            // транзакция), либо откат целиком стирает то, что успели сохранить для healthyBefore.
            ExecutionInstance resumedBefore = executionRepository.findById(healthyBeforeId).orElseThrow();
            assertThat(resumedBefore.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(resumedBefore.getCurrentStepIndex()).isEqualTo(1);
            assertThat(resumedBefore.getWaitTimeoutAt()).isNotNull();

            ExecutionInstance resumedAfter = executionRepository.findById(healthyAfterId).orElseThrow();
            assertThat(resumedAfter.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(resumedAfter.getCurrentStepIndex()).isEqualTo(1);
            assertThat(resumedAfter.getWaitTimeoutAt()).isNotNull();

            // Отравленный инстанс остался RUNNING на том же шаге — его собственная транзакция
            // откатилась из-за constraint violation, залогирована и НЕ распространилась дальше.
            ExecutionInstance stillPoisoned = executionRepository.findById(poisonedId).orElseThrow();
            assertThat(stillPoisoned.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
            assertThat(stillPoisoned.getCurrentStepIndex()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Негатив: завершённые инстансы не воскрешаются")
    class TerminalInstancesNotResurrected {

        @Test
        @DisplayName("COMPLETED-инстанс игнорируется resume-раннером")
        void completedInstanceIsIgnored() {
            ExecutionInstance completedInstance = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.COMPLETED)
                    .currentStepIndex(3)
                    .completedAt(LocalDateTime.now())
                    .build();
            ExecutionInstance saved = executionRepository.save(completedInstance);

            executionResumeRunner.run(null);

            ExecutionInstance reloaded = executionRepository.findById(saved.getId()).orElseThrow();
            // не тронут: статус/шаг/stepHistory не изменились
            assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(reloaded.getCurrentStepIndex()).isEqualTo(3);

            long stepCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM step_executions WHERE execution_instance_id = ?",
                    Long.class, saved.getId());
            assertThat(stepCount).isZero();
        }

        @Test
        @DisplayName("ABORTED-инстанс игнорируется resume-раннером")
        void abortedInstanceIsIgnored() {
            ExecutionInstance abortedInstance = ExecutionInstance.builder()
                    .sequenceId(sequenceId)
                    .aircraftId(AIRCRAFT_ID)
                    .flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.ABORTED)
                    .currentStepIndex(1)
                    .completedAt(LocalDateTime.now())
                    .build();
            ExecutionInstance saved = executionRepository.save(abortedInstance);

            executionResumeRunner.run(null);

            ExecutionInstance reloaded = executionRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(reloaded.getCurrentStepIndex()).isEqualTo(1);

            long stepCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM step_executions WHERE execution_instance_id = ?",
                    Long.class, saved.getId());
            assertThat(stepCount).isZero();
        }

        @Test
        @DisplayName("findAllActive() не возвращает COMPLETED/ABORTED инстансы")
        void findAllActiveExcludesTerminalInstances() {
            ExecutionInstance completedInstance = ExecutionInstance.builder()
                    .sequenceId(sequenceId).aircraftId(AIRCRAFT_ID).flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.COMPLETED).currentStepIndex(3).completedAt(LocalDateTime.now())
                    .build();
            ExecutionInstance abortedInstance = ExecutionInstance.builder()
                    .sequenceId(sequenceId).aircraftId(AIRCRAFT_ID).flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.ABORTED).currentStepIndex(1).completedAt(LocalDateTime.now())
                    .build();
            ExecutionInstance runningInstance = ExecutionInstance.builder()
                    .sequenceId(sequenceId).aircraftId(AIRCRAFT_ID).flightNumber(FLIGHT_NUMBER)
                    .status(ExecutionStatus.RUNNING).currentStepIndex(1)
                    .build();

            executionRepository.save(completedInstance);
            executionRepository.save(abortedInstance);
            ExecutionInstance savedRunning = executionRepository.save(runningInstance);

            List<ExecutionInstance> active = executionRepository.findAllActive();

            assertThat(active).extracting(ExecutionInstance::getId).containsExactly(savedRunning.getId());
        }
    }

    /**
     * processEvent — @ApplicationModuleListener (@Async) — ждём результат поллингом,
     * как и остальные сценарные тесты модуля execution (см. P1_3_InstanceContextPersistenceScenarioIntTest).
     */
    private ExecutionInstance awaitInstance(java.util.function.Predicate<ExecutionInstance> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        ExecutionInstance last = null;
        while (System.currentTimeMillis() < deadline) {
            last = executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                    .getContent().stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId) && AIRCRAFT_ID.equals(i.getAircraftId()))
                    .findFirst()
                    .orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private ExecutionInstance findInstance() {
        List<ExecutionInstance> active = executionRepository.findActiveByAircraftId(AIRCRAFT_ID);
        return active.stream()
                .filter(i -> i.getSequenceId().equals(sequenceId))
                .findFirst()
                .or(() -> executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 100))
                        .getContent().stream()
                        .filter(i -> i.getSequenceId().equals(sequenceId) && AIRCRAFT_ID.equals(i.getAircraftId()))
                        .findFirst())
                .orElseThrow();
    }

    /** Эмулирует приход NormalizedEvent, который резолвит WAITING-инстансы (processWaitingInstances). */
    private void triggerProcessEvent() {
        ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent event =
                new ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent(
                        999L,
                        ru.protectinfotrans.eca.MessageType.DOWNLINK,
                        "POSITION_REPORT",
                        AIRCRAFT_ID,
                        FLIGHT_NUMBER,
                        ru.protectinfotrans.eca.FlightStage.OFF,
                        LocalDateTime.now()
                );
        executionService.processEvent(event);
    }
}
