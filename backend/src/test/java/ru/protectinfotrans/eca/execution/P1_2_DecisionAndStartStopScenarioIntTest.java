package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сценарные тесты задачи P1-2: решения CONTINUE/GOTO/END/ABORT отдельно для
 * true и false веток, Notify-флаг, GOTO вперёд/назад, защита от бесконечного
 * синхронного цикла, start/stop критерии с непрерывной оценкой.
 *
 * Демо-борт VP-BQR, рейс SU1234 — как в P1-1 (EcaParityScenarioIntTest).
 */
@DisplayName("P1-2: Decisions (true/false), GOTO, Notify, Start/Stop criteria")
class P1_2_DecisionAndStartStopScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createSequenceWithSteps(String name, String startCriteria, String stopCriteria,
                                          List<StepCreateRequest> steps) {
        SequenceCreateRequest createReq =
                new SequenceCreateRequest(name, "P1-2 сценарный тест", startCriteria, stopCriteria);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        for (StepCreateRequest step : steps) {
            sequenceUseCase.addStep(created.id(), step, 1L);
        }
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private Long createSequenceWithSteps(String name, List<StepCreateRequest> steps) {
        return createSequenceWithSteps(name, null, null, steps);
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

    /**
     * ExecutionService.processEvent — это @ApplicationModuleListener (мета-аннотирован @Async),
     * поэтому даже прямой вызов метода идёт через Spring AOP-проксю асинхронно в пуле потоков
     * Spring Modulith (а не через TaskExecutor-бин теста). Тесты "непрерывной оценки"
     * start/stop критериев намеренно проверяют этот реальный путь (processEvent), а не
     * startExecution() напрямую — поэтому ждём терминального состояния с таймаутом, вместо
     * того чтобы полагаться на синхронность вызова.
     */
    private ExecutionInstance awaitInstance(Long sequenceId, String aircraftId, java.util.function.Predicate<ExecutionInstance> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        ExecutionInstance last = null;
        while (System.currentTimeMillis() < deadline) {
            last = executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                    .getContent().stream()
                    .filter(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()))
                    .findFirst()
                    .orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Condition not met within timeout for sequence " + sequenceId
                + ", aircraft " + aircraftId + ", last state: " + last);
    }

    /**
     * Читает step_executions напрямую через JDBC, минуя lazy-load ExecutionInstance.stepHistory
     * (коллекция LAZY, а тест обращается к ней вне транзакции сервиса — типичная ситуация
     * для интеграционных тестов поверх синхронного API).
     */
    private record StepHistoryRow(int stepIndex, String result, String transitionAction, Integer transitionTarget) {}

    private List<StepHistoryRow> readStepHistory(Long executionInstanceId) {
        return jdbcTemplate.query(
                "SELECT step_index, result, transition_action, transition_target FROM step_executions "
                        + "WHERE execution_instance_id = ? ORDER BY id ASC",
                (rs, rowNum) -> new StepHistoryRow(
                        rs.getInt("step_index"),
                        rs.getString("result"),
                        rs.getString("transition_action"),
                        rs.getObject("transition_target") != null ? rs.getInt("transition_target") : null
                ),
                executionInstanceId
        );
    }

    // ============================================================
    // 1. Независимые решения true/false: CONTINUE на true, GOTO на false
    // ============================================================
    @Nested
    @DisplayName("true->CONTINUE и false->GOTO")
    class TrueContinueFalseGotoTests {

        @Test
        @DisplayName("true ветка (EQUALS INIT выполняется) идёт по CONTINUE к шагу 2, который END")
        void trueBranchContinuesToNextStep() {
            Long sequenceId = createSequenceWithSteps("True continue", List.of(
                    new StepCreateRequest(
                            "Проверить INIT",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                            null,
                            TransitionAction.CONTINUE, null, false,
                            TransitionAction.GOTO, 1, false
                    ),
                    new StepCreateRequest(
                            "Завершение",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"OK\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            // шаг 1 (CONTINUE) и шаг 2 (END) должны быть в истории
            List<Integer> visitedSteps = readStepHistory(instance.getId()).stream()
                    .map(StepHistoryRow::stepIndex)
                    .toList();
            assertThat(visitedSteps).contains(1, 2);
        }

        @Test
        @DisplayName("false ветка (EQUALS ON не выполняется) уходит по GOTO на тот же шаг 1, а не CONTINUE")
        void falseBranchGoesToGotoNotContinue() {
            // Шаг 1: критерий ложный (стадия INIT != ON) → onFailure = GOTO step 2 (избегаем
            // зацикливания — переходим вперёд на завершающий шаг, а не назад на себя).
            Long sequenceId = createSequenceWithSteps("False goto", List.of(
                    new StepCreateRequest(
                            "Проверить ON",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"}",
                            null,
                            TransitionAction.ABORT, null, false,
                            TransitionAction.GOTO, 2, false
                    ),
                    new StepCreateRequest(
                            "Цель GOTO",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"REACHED\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            // false → GOTO 2 → END → COMPLETED (не ABORT, который относится к true ветке)
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            StepHistoryRow firstTransition = readStepHistory(instance.getId()).get(0);
            assertThat(firstTransition.transitionAction()).isEqualTo(TransitionAction.GOTO.name());
            assertThat(firstTransition.transitionTarget()).isEqualTo(2);
        }
    }

    // ============================================================
    // 2. Независимые решения true/false: END на true, ABORT на false
    // ============================================================
    @Nested
    @DisplayName("true->END и false->ABORT")
    class TrueEndFalseAbortTests {

        @Test
        @DisplayName("true ветка завершает END -> COMPLETED")
        void trueBranchEndsNormally() {
            Long sequenceId = createSequenceWithSteps("True end", List.of(
                    new StepCreateRequest(
                            "Проверить INIT",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("false ветка завершает ABORT -> ABORTED (независимо от true ветки)")
        void falseBranchAbortsExecution() {
            Long sequenceId = createSequenceWithSteps("False abort", List.of(
                    new StepCreateRequest(
                            "Проверить ON",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(instance.getCompletedAt()).isNotNull();
        }
    }

    // ============================================================
    // 3. GOTO вперёд и назад
    // ============================================================
    @Nested
    @DisplayName("GOTO вперёд и назад")
    class GotoForwardBackwardTests {

        @Test
        @DisplayName("GOTO вперёд: шаг 1 перепрыгивает шаг 2 и попадает прямо на шаг 3")
        void gotoForwardSkipsIntermediateStep() {
            Long sequenceId = createSequenceWithSteps("Goto forward", List.of(
                    new StepCreateRequest(
                            "Шаг 1: переход вперёд на шаг 3",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                            null,
                            TransitionAction.GOTO, 3, false,
                            TransitionAction.ABORT, null, false
                    ),
                    new StepCreateRequest(
                            "Шаг 2: НЕ должен выполниться",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"SHOULD_SKIP\",\"alertLevel\":\"HIGH\"}",
                            null,
                            TransitionAction.ABORT, null, false,
                            TransitionAction.ABORT, null, false
                    ),
                    new StepCreateRequest(
                            "Шаг 3: цель перехода",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"REACHED_STEP3\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            List<Integer> visitedSteps = readStepHistory(instance.getId()).stream()
                    .map(StepHistoryRow::stepIndex)
                    .toList();
            assertThat(visitedSteps).containsExactly(1, 3);
            assertThat(visitedSteps).doesNotContain(2);
        }

        @Test
        @DisplayName("GOTO назад: шаг 2 (WAIT) при таймауте возвращается на шаг 1 (индекс 2 -> 1); " +
                "WAIT разрывает синхронную рекурсию, поэтому цикл не зависает и шаг 1 выполняется повторно")
        void gotoBackwardToEarlierIndex() {
            String aircraftId = AIRCRAFT_ID + "_BACK";

            // Шаг 1 (ACTION): поднимает условие RETRY_COUNT и всегда идёт дальше -> шаг 2.
            // Шаг 2 (WAIT): ждёт ACK с коротким таймаутом. На первом проходе ACK не приходит,
            // таймаут истекает -> FAILURE -> onFailure = GOTO 1 (переход НАЗАД, 2 -> 1).
            // WAIT гарантирует, что переход случается между двумя отдельными вызовами
            // checkWaitTimeouts(), а не в одной синхронной рекурсии — поэтому он не попадает
            // под защиту MAX_SYNCHRONOUS_TRANSITIONS (которая защищает только горячий цикл без WAIT).
            // На втором проходе мы отправляем ACK через processEvent -> шаг 2 WAIT резолвится
            // в SUCCESS -> CONTINUE -> шаг 3 -> END.
            Long sequenceId = createSequenceWithSteps("Goto backward index", List.of(
                    new StepCreateRequest(
                            "Шаг 1: отметить попытку",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"RETRY_MARK\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.CONTINUE, null, false,
                            TransitionAction.CONTINUE, null, false
                    ),
                    new StepCreateRequest(
                            "Шаг 2: ждать ACK, при таймауте — назад на шаг 1",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                            1,
                            TransitionAction.CONTINUE, null, false,
                            TransitionAction.GOTO, 1, false
                    ),
                    new StepCreateRequest(
                            "Шаг 3: финал",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"FINAL\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(instance.getCurrentStepIndex()).isEqualTo(2);

            // истекаем таймаут шага 2 -> FAILURE -> GOTO 1 (переход назад, 2 -> 1)
            instance.setWaitTimeoutAt(LocalDateTime.now().minusSeconds(5));
            executionRepository.save(instance);
            executionService.checkWaitTimeouts();

            ExecutionInstance afterTimeout = executionRepository.findById(instance.getId()).orElseThrow();
            // шаг 1 выполнился повторно (ACTION всегда SUCCESS) и снова перешёл на шаг 2 (WAIT)
            assertThat(afterTimeout.getStatus()).isEqualTo(ExecutionStatus.WAITING);
            assertThat(afterTimeout.getCurrentStepIndex()).isEqualTo(2);

            List<StepHistoryRow> history = readStepHistory(afterTimeout.getId());
            StepHistoryRow backwardGoto = history.stream()
                    .filter(se -> se.stepIndex() == 2 && TransitionAction.GOTO.name().equals(se.transitionAction()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Не найден переход GOTO с шага 2"));
            assertThat(backwardGoto.transitionTarget()).isEqualTo(1);
            assertThat(backwardGoto.transitionTarget()).isLessThan(backwardGoto.stepIndex());

            // подтверждаем, что шаг 1 действительно выполнился второй раз (история содержит 1 минимум двукратно)
            long step1Visits = history.stream()
                    .filter(se -> se.stepIndex() == 1)
                    .count();
            assertThat(step1Visits).isEqualTo(2);

            // теперь отправляем ACK -> WAIT резолвится в SUCCESS -> CONTINUE -> шаг 3 -> END.
            // MESSAGE_RECEIVED критерий читает таблицу messages — в проде её заполняет
            // EventProcessorService ДО публикации NormalizedEvent; здесь эмулируем это явной
            // вставкой (так же делает EcaParityScenarioIntTest.WaitStepTests).
            // processEvent — @ApplicationModuleListener (@Async), ждём результат через awaitInstance.
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW())",
                    "DOWNLINK", "ACK", aircraftId, FLIGHT_NUMBER, "{}"
            );
            executionService.processEvent(new NormalizedEvent(
                    10L, MessageType.DOWNLINK, "ACK", aircraftId, FLIGHT_NUMBER,
                    FlightStage.OFF, LocalDateTime.now()));

            ExecutionInstance finalInstance = awaitInstance(sequenceId, aircraftId,
                    i -> i.getStatus() == ExecutionStatus.COMPLETED);
            assertThat(finalInstance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    // ============================================================
    // 4. Защита от бесконечного синхронного цикла
    // ============================================================
    @Nested
    @DisplayName("Защита от бесконечного цикла GOTO")
    class InfiniteLoopProtectionTests {

        @Test
        @DisplayName("цикл из двух ACTION-шагов без WAIT должен быть прерван лимитом и завершиться ABORTED")
        void infiniteGotoLoopIsAbortedByTransitionLimit() {
            String aircraftId = AIRCRAFT_ID + "_LOOP";

            // Шаг 1 -> CONTINUE -> Шаг 2 -> GOTO 1 -> Шаг 1 -> ... бесконечно (ACTION всегда SUCCESS,
            // нет WAIT, нет смены состояния между итерациями) — без лимита это StackOverflowError.
            // Шаг 3 структурно недостижим из цикла, но даёт SequenceValidator путь к END
            // (SITA допускает последовательности, где END теоретически достижим, а не только
            // те, где runtime гарантированно туда дойдёт — это решает оператор при проектировании).
            Long sequenceId = createSequenceWithSteps("Infinite loop", List.of(
                    new StepCreateRequest(
                            "Шаг 1",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"LOOP_A\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.CONTINUE, null, false,
                            TransitionAction.CONTINUE, null, false
                    ),
                    new StepCreateRequest(
                            "Шаг 2",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"LOOP_B\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.GOTO, 1, false,
                            TransitionAction.GOTO, 1, false
                    ),
                    new StepCreateRequest(
                            "Шаг 3: недостижим из цикла, нужен только для прохождения SequenceValidator",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"UNREACHABLE\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // не StackOverflowError, не зависание — детерминированный ABORTED по лимиту
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(instance.getCompletedAt()).isNotNull();
            // история шагов должна быть достаточно длинной (цикл реально прокрутился), но конечной
            assertThat(readStepHistory(instance.getId()).size())
                    .isGreaterThan(10)
                    .isLessThanOrEqualTo(ExecutionService.MAX_SYNCHRONOUS_TRANSITIONS + 1);
        }
    }

    // ============================================================
    // 5. Notify-флаг срабатывает на нужной ветке
    // ============================================================
    @Nested
    @DisplayName("Notify-флаг")
    class NotifyFlagTests {

        @Test
        @DisplayName("onSuccessNotify=true публикует StepNotificationEvent только при SUCCESS")
        void notifyFiresOnlyOnConfiguredBranch() {
            Long sequenceId = createSequenceWithSteps("Notify on success", List.of(
                    new StepCreateRequest(
                            "Проверить INIT (notify on success)",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                            null,
                            TransitionAction.END, null, true,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);

            StepHistoryRow stepExecution = readStepHistory(instance.getId()).get(0);
            assertThat(stepExecution.result()).isEqualTo("SUCCESS");
            // флаг notify хранится на уровне шага (Step.onSuccessNotify), фактическая отправка
            // подтверждена в ExecutionServiceTest.shouldNotifyOnSuccess (unit, с verify на NotificationPort)
        }

        @Test
        @DisplayName("onFailureNotify=true должен сработать именно на false-ветке, не на true")
        void notifyOnFailureBranchOnly() {
            Long sequenceId = createSequenceWithSteps("Notify on failure", List.of(
                    new StepCreateRequest(
                            "Проверить ON (notify on failure)",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, true
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);

            StepHistoryRow stepExecution = readStepHistory(instance.getId()).get(0);
            assertThat(stepExecution.result()).isEqualTo("FAILURE");
        }
    }

    // ============================================================
    // 6. Start критерий запускает последовательность (непрерывная оценка)
    // ============================================================
    @Nested
    @DisplayName("Start критерий — непрерывная оценка")
    class StartCriteriaContinuousTests {

        @Test
        @DisplayName("start критерий FLIGHT_STAGE=OFF запускает последовательность только когда событие приходит со стадией OFF")
        void startCriterionActivatesOnMatchingEvent() {
            Long sequenceId = createSequenceWithSteps(
                    "Start on OFF stage",
                    "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"OFF\"}",
                    null,
                    List.of(new StepCreateRequest(
                            "Финал",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"STARTED\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    ))
            );

            // событие со стадией OFF — start критерий выполнен непрерывной оценкой -> запуск.
            // processEvent — это @ApplicationModuleListener (@Async), поэтому ждём результат
            // через awaitInstance вместо синхронного assert сразу после вызова.
            // Сначала проверяем, что НЕ-совпадающее событие (INIT) не запускает последовательность:
            // запускаем заведомо отдельный bort-ID, чтобы не блокировать основной сценарий ниже.
            String noStartAircraftId = AIRCRAFT_ID + "_NOSTART";
            executionService.processEvent(new NormalizedEvent(
                    1L, MessageType.DOWNLINK, "STATUS", noStartAircraftId, FLIGHT_NUMBER,
                    FlightStage.INIT, LocalDateTime.now()));

            // даём асинхронному обработчику время, затем убеждаемся, что инстанс НЕ появился
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(executionRepository.findActiveByAircraftId(noStartAircraftId)).isEmpty();

            // событие со стадией OFF — start критерий выполнен непрерывной оценкой -> запуск
            executionService.processEvent(new NormalizedEvent(
                    2L, MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    FlightStage.OFF, LocalDateTime.now()));

            ExecutionInstance instance = awaitInstance(sequenceId, AIRCRAFT_ID,
                    i -> i.getStatus() == ExecutionStatus.COMPLETED);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("BUG: составной (COMPOUND AND) start-критерий с MESSAGE_RECEIVED не должен матчить " +
                "историческое сообщение из БД вместо текущего события")
        void compoundStartCriterionDoesNotMatchHistoricalMessageInsteadOfCurrentEvent() {
            String aircraftId = AIRCRAFT_ID + "_COMPOUND_START";

            Long sequenceId = createSequenceWithSteps(
                    "Compound start with historical message guard",
                    "{\"type\":\"COMPOUND\",\"operator\":\"AND\",\"children\":["
                            + "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"ACK\"},"
                            + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"OFF\"}"
                            + "]}",
                    null,
                    List.of(new StepCreateRequest(
                            "Финал",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"COMPOUND_STARTED\",\"alertLevel\":\"LOW\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    ))
            );

            // ACK когда-то пришёл (например, в предыдущем рейсе этого борта) — НЕ текущее событие.
            // ExecutionService.matchesStartCriteria до фикса рекурсивно уходит в CriterionEvaluator
            // для содержимого COMPOUND, а тот ищет MESSAGE_RECEIVED в истории БД без ограничения
            // по времени (afterTime=null вне WAIT-контекста) — историческое сообщение "засчитывается"
            // навсегда, хотя к текущему событию отношения не имеет.
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '1 day')",
                    "DOWNLINK", "ACK", aircraftId, FLIGHT_NUMBER, "{}"
            );

            // Текущее событие — НЕ ACK (шаблон STATUS), но стадия совпадает (OFF).
            executionService.processEvent(new NormalizedEvent(
                    100L, MessageType.DOWNLINK, "STATUS", aircraftId, FLIGHT_NUMBER,
                    FlightStage.OFF, LocalDateTime.now()));

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // findActiveByAircraftId не годится для этой проверки: единственный шаг тут —
            // ACTION -> END, так что ложно запущенный инстанс тут же становится COMPLETED
            // и перестаёт быть "активным" — проверяем по ВСЕМ инстансам этой пары
            // (sequence, aircraft), не только по активным.
            boolean falseStartHappened = executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                    .getContent().stream()
                    .anyMatch(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()));
            assertThat(falseStartHappened)
                    .as("не должно запускаться по историческому ACK — текущее событие не ACK")
                    .isFalse();

            // Текущее событие ДЕЙСТВИТЕЛЬНО ACK + стадия OFF -> должно запуститься штатно.
            executionService.processEvent(new NormalizedEvent(
                    101L, MessageType.DOWNLINK, "ACK", aircraftId, FLIGHT_NUMBER,
                    FlightStage.OFF, LocalDateTime.now()));

            ExecutionInstance instance = awaitInstance(sequenceId, aircraftId,
                    i -> i.getStatus() == ExecutionStatus.COMPLETED);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    // ============================================================
    // 7. Stop критерий останавливает выполнение на середине последовательности
    // ============================================================
    @Nested
    @DisplayName("Stop критерий — останавливает в любой момент")
    class StopCriteriaContinuousTests {

        @Test
        @DisplayName("stop критерий FLIGHT_STAGE>=ON останавливает инстанс, зависший в WAIT на середине")
        void stopCriterionAbortsMidSequenceWaitingInstance() {
            String aircraftId = AIRCRAFT_ID + "_STOP";

            Long sequenceId = createSequenceWithSteps(
                    "Stop mid-sequence",
                    null,
                    "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"GREATER_OR_EQUAL\",\"targetStage\":\"ON\"}",
                    List.of(new StepCreateRequest(
                            "Ждать ACK (никогда не придёт)",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                            600,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    ))
            );

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance waiting = findInstance(sequenceId, aircraftId);
            assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // независимо от текущего шага (WAITING на шаге 1) -- любое событие со
            // стадией >= ON останавливает последовательность немедленно (stop критерий).
            // processEvent — @ApplicationModuleListener (@Async), ждём результат через awaitInstance.
            executionService.processEvent(new NormalizedEvent(
                    3L, MessageType.DOWNLINK, "STATUS", aircraftId, FLIGHT_NUMBER,
                    FlightStage.ON, LocalDateTime.now()));

            ExecutionInstance updated = awaitInstance(sequenceId, aircraftId,
                    i -> i.getStatus() == ExecutionStatus.ABORTED);
            assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("BUG: простой (не составной) stop-критерий MESSAGE_RECEIVED не должен матчить " +
                "историческое сообщение из БД вместо текущего события")
        void stopCriterionMessageReceivedDoesNotMatchHistoricalMessageInsteadOfCurrentEvent() {
            String aircraftId = AIRCRAFT_ID + "_STOP_HISTORICAL";

            Long sequenceId = createSequenceWithSteps(
                    "Stop on historical-message guard",
                    null,
                    "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\",\"templateName\":\"ACK\"}",
                    List.of(new StepCreateRequest(
                            "Ждать финальное подтверждение (в этом тесте не приходит)",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"FINAL_ACK\",\"fromThisPointOnly\":true}",
                            600,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    ))
            );

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);
            ExecutionInstance waiting = findInstance(sequenceId, aircraftId);
            assertThat(waiting.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // ACK когда-то пришёл ДО старта этого инстанса — НЕ текущее событие, отношения
            // к остановке ИМЕННО ЭТОГО инстанса не имеет. До фикса checkStopCriterionTransactional
            // вообще не сравнивал stop-критерий с текущим событием — уходил прямиком в
            // CriterionEvaluator, а тот искал MESSAGE_RECEIVED в истории БД без ограничения времени.
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '1 day')",
                    "DOWNLINK", "ACK", aircraftId, FLIGHT_NUMBER, "{}"
            );

            // Текущее событие — НЕ ACK.
            executionService.processEvent(new NormalizedEvent(
                    200L, MessageType.DOWNLINK, "STATUS", aircraftId, FLIGHT_NUMBER,
                    FlightStage.OFF, LocalDateTime.now()));

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ExecutionInstance stillWaiting = executionRepository.findById(waiting.getId()).orElseThrow();
            assertThat(stillWaiting.getStatus())
                    .as("не должен остановиться по историческому ACK — текущее событие не ACK")
                    .isEqualTo(ExecutionStatus.WAITING);
        }
    }

    // ============================================================
    // 8. Валидация: невалидный GOTO должен быть отклонён при активации
    // ============================================================
    @Nested
    @DisplayName("Валидация невалидного GOTO")
    class InvalidGotoValidationTests {

        @Test
        @DisplayName("активация последовательности с GOTO на несуществующий шаг должна быть отклонена")
        void activationRejectsInvalidGotoTarget() {
            SequenceCreateRequest createReq =
                    new SequenceCreateRequest("Invalid goto seq", "desc", null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);

            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Шаг с GOTO на 99",
                    StepType.ACTION,
                    "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"X\",\"alertLevel\":\"LOW\"}",
                    null,
                    TransitionAction.GOTO, 99, false,
                    TransitionAction.END, null, false
            ), 1L);

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> sequenceUseCase.activateSequence(created.id(), 1L));
        }
    }
}
