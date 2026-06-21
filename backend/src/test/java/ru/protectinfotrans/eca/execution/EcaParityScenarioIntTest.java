package ru.protectinfotrans.eca.execution;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сценарные тесты паритета с SITA Sequencer (задача P1-1):
 * 3 типа шагов (ACTION/EVALUATE/WAIT) и 4 семейства критериев
 * (message received, flight stage, position, time) с операторами и AND/OR.
 *
 * Демо-борт VP-BQR, рейс SU1234 — как и в ExecutionFlowIntTest.
 */
@DisplayName("ECA Parity Scenario Tests (P1-1)")
class EcaParityScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createSequenceWithSteps(String name, List<StepCreateRequest> steps) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "Тест паритета P1-1", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        for (StepCreateRequest step : steps) {
            sequenceUseCase.addStep(created.id(), step, 1L);
        }
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

    // ============================================================
    // 1. Типы шагов: ACTION
    // ============================================================
    @Nested
    @DisplayName("ACTION step")
    class ActionStepTests {

        @Test
        @DisplayName("RAISE_CONDITION с уровнем алерта HIGH должен выполниться и завершить END")
        void raiseConditionCompletesSequence() {
            Long sequenceId = createSequenceWithSteps("ACTION raise condition", List.of(
                    new StepCreateRequest(
                            "Поднять алерт",
                            StepType.ACTION,
                            "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"NO_POSITION\",\"alertLevel\":\"HIGH\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("SEND_UPLINK computer-generated должен выполниться успешно")
        void sendUplinkComputerGeneratedSucceeds() {
            Long sequenceId = createSequenceWithSteps("ACTION send uplink", List.of(
                    new StepCreateRequest(
                            "Отправить uplink",
                            StepType.ACTION,
                            "{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"REQUEST_POSITION\","
                                    + "\"uplinkOrigin\":\"COMPUTER_GENERATED\",\"params\":{}}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("WAIT_TIME должен корректно перевести unit=MIN в секунды и завершиться через END")
        void waitTimeWithMinutesUnitCompletesSequence() {
            // Примечание: ACTION WAIT_TIME выставляет waitStartedAt/waitTimeoutAt с учётом unit,
            // но синхронный ExecutionService.advanceExecution тут же выполняет onSuccessAction —
            // реальная блокировка до истечения таймаута требует durable-планировщика (см. P1-5).
            // Конвертация durationSeconds+unit→секунды покрыта точечно в ActionStepRuleTest.
            Long sequenceId = createSequenceWithSteps("ACTION wait time minutes", List.of(
                    new StepCreateRequest(
                            "Пауза 1 минута",
                            StepType.ACTION,
                            "{\"actionType\":\"WAIT_TIME\",\"durationSeconds\":1,\"unit\":\"MIN\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.END, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(instance.getWaitStartedAt()).isNotNull();
            assertThat(instance.getWaitTimeoutAt()).isAfter(instance.getWaitStartedAt().plusSeconds(59));
        }
    }

    // ============================================================
    // 2. Типы шагов: EVALUATE IF (мгновенная проверка, не блокирует)
    // ============================================================
    @Nested
    @DisplayName("EVALUATE IF step")
    class EvaluateStepTests {

        @Test
        @DisplayName("должен мгновенно вернуть SUCCESS если критерий FLIGHT_STAGE выполнен")
        void evaluateFlightStageSucceedsImmediately() {
            Long sequenceId = createSequenceWithSteps("EVALUATE flight stage", List.of(
                    new StepCreateRequest(
                            "Проверить стадию",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            // стартовая стадия по умолчанию = INIT (buildDefaultContext)
            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("должен мгновенно вернуть FAILURE и пойти по onFailure без ожидания")
        void evaluateFlightStageFailsImmediately() {
            Long sequenceId = createSequenceWithSteps("EVALUATE flight stage fail", List.of(
                    new StepCreateRequest(
                            "Проверить стадию ON",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            // EVALUATE не блокирует — мгновенно идёт по onFailure=ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }

    // ============================================================
    // 3. Типы шагов: WAIT FOR (блокирует, таймаут → false)
    // ============================================================
    @Nested
    @DisplayName("WAIT FOR step")
    class WaitStepTests {

        @Test
        @DisplayName("должен оставаться WAITING пока критерий не выполнен")
        void waitStaysWaitingUntilCriterionMet() {
            Long sequenceId = createSequenceWithSteps("WAIT message", List.of(
                    new StepCreateRequest(
                            "Ждать ACK",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                            300,
                            TransitionAction.END, null, false,
                            TransitionAction.CONTINUE, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
        }

        @Test
        @DisplayName("таймаут WAIT FOR должен вернуть false и пойти по onFailure")
        void waitTimeoutResolvesToFalse() {
            Long sequenceId = createSequenceWithSteps("WAIT timeout false", List.of(
                    new StepCreateRequest(
                            "Ждать ACK (короткий таймаут)",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                            1,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);

            // принудительно истекаем таймаут (детерминированно, без реального sleep)
            instance.setWaitTimeoutAt(LocalDateTime.now().minusSeconds(5));
            executionRepository.save(instance);
            executionService.checkWaitTimeouts();

            ExecutionInstance updated = executionRepository.findById(instance.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("fromThisPointOnly=true должен отсечь сообщение, полученное ДО старта WAIT")
        void fromThisPointOnlyIgnoresHistoricalMessage() {
            String aircraftId = AIRCRAFT_ID + "_HIST";

            // историческое сообщение — получено задолго до старта последовательности
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    "DOWNLINK", "ACK", aircraftId, FLIGHT_NUMBER, "{}",
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(2))
            );

            Long sequenceId = createSequenceWithSteps("WAIT from this point only", List.of(
                    new StepCreateRequest(
                            "Ждать ACK с этой точки",
                            StepType.WAIT,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"ACK\",\"fromThisPointOnly\":true}",
                            300,
                            TransitionAction.END, null, false,
                            TransitionAction.CONTINUE, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // историческое сообщение отсечено fromThisPointOnly — шаг остаётся в WAITING, не SUCCESS
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.WAITING);
        }
    }

    // ============================================================
    // 4. Критерий: MESSAGE_RECEIVED (downlink/uplink/ground)
    // ============================================================
    @Nested
    @DisplayName("MESSAGE_RECEIVED criterion")
    class MessageReceivedCriterionTests {

        @Test
        @DisplayName("EVALUATE должен найти существующее downlink-сообщение по шаблону")
        void evaluateFindsExistingDownlinkMessage() {
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW())",
                    "DOWNLINK", "STATUS_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}"
            );

            Long sequenceId = createSequenceWithSteps("EVALUATE message received", List.of(
                    new StepCreateRequest(
                            "Проверить STATUS_REPORT",
                            StepType.EVALUATE,
                            "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"STATUS_REPORT\",\"fromThisPointOnly\":false}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    // ============================================================
    // 5. Критерий: FLIGHT_STAGE (операторы =,>,<,>=,<=,not)
    // ============================================================
    @Nested
    @DisplayName("FLIGHT_STAGE criterion operators")
    class FlightStageCriterionTests {

        @Test
        @DisplayName("NOT_EQUALS: должен пройти если стадия отличается от целевой")
        void notEqualsOperatorPasses() {
            Long sequenceId = createSequenceWithSteps("FLIGHT_STAGE not equals", List.of(
                    new StepCreateRequest(
                            "Стадия != ON",
                            StepType.EVALUATE,
                            "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"NOT_EQUALS\",\"targetStage\":\"ON\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    // ============================================================
    // 6. Критерий: POSITION_REPORTED (reported/not reported, source, estimated игнорируется)
    // ============================================================
    @Nested
    @DisplayName("POSITION_REPORTED criterion")
    class PositionReportedCriterionTests {

        @Test
        @DisplayName("должен найти фактический позиционный отчёт ACARS в окне")
        void findsActualAcarsPositionReport() {
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ACARS"
            );

            Long sequenceId = createSequenceWithSteps("POSITION reported ACARS", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин (ACARS)",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30,\"source\":\"ACARS\"}",
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
        @DisplayName("должен найти фактический позиционный отчёт ADS-B в окне (P2-5: источник ADS_B)")
        void findsActualAdsbPositionReport() {
            String aircraftId = AIRCRAFT_ID + "_ADSB";
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}", "ADS_B"
            );

            Long sequenceId = createSequenceWithSteps("POSITION reported ADS_B", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин (ADS_B)",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30,\"source\":\"ADS_B\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("должен найти фактический позиционный отчёт RADAR в окне (P2-5: источник RADAR)")
        void findsActualRadarPositionReport() {
            String aircraftId = AIRCRAFT_ID + "_RADAR";
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}", "RADAR"
            );

            Long sequenceId = createSequenceWithSteps("POSITION reported RADAR", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин (RADAR)",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30,\"source\":\"RADAR\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("источник-фильтр: критерий ждёт RADAR, пришла ACARS — не матчит (P2-5)")
        void sourceFilterDoesNotMatchDifferentSource() {
            String aircraftId = AIRCRAFT_ID + "_SRC_MISMATCH";
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}", "ACARS"
            );

            Long sequenceId = createSequenceWithSteps("POSITION source mismatch", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин (ждём RADAR)",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30,\"source\":\"RADAR\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // пришёл ACARS, критерий ждёт RADAR -> не матчит -> FAILURE -> ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("оценочная (estimated) позиция RADAR должна игнорироваться так же, как и любой другой источник (P2-5)")
        void ignoresEstimatedPositionFromRadar() {
            String aircraftId = AIRCRAFT_ID + "_RADAR_ESTIMATED";
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, TRUE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}", "RADAR"
            );

            Long sequenceId = createSequenceWithSteps("POSITION ignores estimated radar", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин (RADAR)",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30,\"source\":\"RADAR\"}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // единственный RADAR-отчёт — estimated -> игнорируется -> FAILURE -> ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("оценочная (estimated) позиция должна игнорироваться — критерий не находит отчёт")
        void ignoresEstimatedPosition() {
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), ?, TRUE)",
                    "DOWNLINK", "POSITION_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}", "ADS_B"
            );

            Long sequenceId = createSequenceWithSteps("POSITION ignores estimated", List.of(
                    new StepCreateRequest(
                            "Позиция за 30 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            // единственная позиция в БД — estimated, значит "reported" (фактически) = false → onFailure = ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("not reported=true должен НЕ срабатывать (FAILURE), если ВС ещё не взлетало (нет Off-таймстампа) — P2-5")
        void notReportedFailsWhenOffTimestampUnknown() {
            // НИКАКОГО Off-события не зафиксировано для этого борта — окно "not reported" ещё
            // не началось (паритет SITA: until взлёта нет ожидаемого потока позиций), поэтому
            // критерий должен вернуть false, а не тривиально true.
            Long sequenceId = createSequenceWithSteps("POSITION not reported no off", List.of(
                    new StepCreateRequest(
                            "Нет позиции за 30 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"reported\":false,\"minutesAgo\":30}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID + "_NOPOS", FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID + "_NOPOS");
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("not reported=true должен пройти (Off зафиксирован, ни одного фактического отчёта после Off) — P2-5")
        void notReportedPassesAfterOffWithNoActualReportSince() {
            String aircraftId = AIRCRAFT_ID + "_NOPOS_OFF";

            // Off зафиксирован 10 минут назад — борт взлетел, окно "not reported" уже активно
            jdbcTemplate.update(
                    "INSERT INTO flight_stage_events (aircraft_id, flight_number, stage, occurred_at) "
                            + "VALUES (?, ?, 'OFF', ?)",
                    aircraftId, FLIGHT_NUMBER, java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(10))
            );

            Long sequenceId = createSequenceWithSteps("POSITION not reported since off", List.of(
                    new StepCreateRequest(
                            "Нет позиции за 30 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"reported\":false,\"minutesAgo\":30}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("not reported=true должен НЕ срабатывать, если фактический отчёт пришёл после Off в пределах окна — P2-5")
        void notReportedFailsWhenActualReportExistsAfterOff() {
            String aircraftId = AIRCRAFT_ID + "_NOPOS_OFF_HASPOS";
            LocalDateTime offTime = LocalDateTime.now().minusMinutes(10);

            jdbcTemplate.update(
                    "INSERT INTO flight_stage_events (aircraft_id, flight_number, stage, occurred_at) "
                            + "VALUES (?, ?, 'OFF', ?)",
                    aircraftId, FLIGHT_NUMBER, java.sql.Timestamp.valueOf(offTime)
            );
            // фактический позиционный отчёт ПОСЛЕ Off, внутри окна 30 мин
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}",
                    java.sql.Timestamp.valueOf(offTime.plusMinutes(5)), "ACARS"
            );

            Long sequenceId = createSequenceWithSteps("POSITION not reported but has report", List.of(
                    new StepCreateRequest(
                            "Нет позиции за 30 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"reported\":false,\"minutesAgo\":30}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // фактический отчёт после Off в пределах окна -> "not reported" = false -> onFailure = ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }

        @Test
        @DisplayName("not reported=true с окном короче времени с Off должен НЕ заглядывать раньше запрошенного окна — P2-5")
        void notReportedWindowDoesNotExceedRequestedMinutesEvenAfterOff() {
            String aircraftId = AIRCRAFT_ID + "_NOPOS_OFF_OLD_REPORT";
            LocalDateTime offTime = LocalDateTime.now().minusHours(2);

            jdbcTemplate.update(
                    "INSERT INTO flight_stage_events (aircraft_id, flight_number, stage, occurred_at) "
                            + "VALUES (?, ?, 'OFF', ?)",
                    aircraftId, FLIGHT_NUMBER, java.sql.Timestamp.valueOf(offTime)
            );
            // отчёт сразу после Off (2 часа назад) — ВНЕ запрошенного окна 30 мин, хотя ПОСЛЕ Off
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, "
                            + "received_at, position_source, is_estimated_position) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)",
                    "DOWNLINK", "POSITION_REPORT", aircraftId, FLIGHT_NUMBER, "{}",
                    java.sql.Timestamp.valueOf(offTime.plusMinutes(2)), "ACARS"
            );

            Long sequenceId = createSequenceWithSteps("POSITION not reported window capped", List.of(
                    new StepCreateRequest(
                            "Нет позиции за 30 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"POSITION_REPORTED\",\"reported\":false,\"minutesAgo\":30}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, aircraftId);
            // старый отчёт (2ч назад) вне окна 30 мин, в пределах окна после Off отчётов нет -> "not reported" = true
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }
    }

    // ============================================================
    // 7. Критерий: TIME_COMPARISON (before/equal/after × ETD/ETA/OOOI ± offset)
    // ============================================================
    @Nested
    @DisplayName("TIME_COMPARISON criterion")
    class TimeComparisonCriterionTests {

        @Test
        @DisplayName("должен вернуть FAILURE если опорное время (ETD) не предоставлено в контексте")
        void failsWhenReferenceTimeMissing() {
            Long sequenceId = createSequenceWithSteps("TIME without reference", List.of(
                    new StepCreateRequest(
                            "После ETD + 10 мин",
                            StepType.EVALUATE,
                            "{\"type\":\"TIME_COMPARISON\",\"operator\":\"IS_AFTER\","
                                    + "\"referencePoint\":\"ETD\",\"offsetMinutes\":10}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            // additionalData в buildDefaultContext не содержит etdTime → reference time = null → FAILURE → ABORT
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }

    // ============================================================
    // 8. AND/OR комбинирование критериев
    // ============================================================
    @Nested
    @DisplayName("COMPOUND (AND/OR) criterion")
    class CompoundCriterionTests {

        @Test
        @DisplayName("AND: оба истинны (FLIGHT_STAGE=INIT AND MESSAGE_RECEIVED) → SUCCESS")
        void andOfFlightStageAndMessageSucceeds() {
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content, received_at) "
                            + "VALUES (?, ?, ?, ?, ?, NOW())",
                    "DOWNLINK", "STATUS_REPORT", AIRCRAFT_ID, FLIGHT_NUMBER, "{}"
            );

            Long sequenceId = createSequenceWithSteps("COMPOUND AND", List.of(
                    new StepCreateRequest(
                            "INIT AND STATUS_REPORT",
                            StepType.EVALUATE,
                            "{\"type\":\"COMPOUND\",\"operator\":\"AND\",\"children\":["
                                    + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"},"
                                    + "{\"type\":\"MESSAGE_RECEIVED\",\"messageType\":\"DOWNLINK\","
                                    + "\"templateName\":\"STATUS_REPORT\",\"fromThisPointOnly\":false}"
                                    + "]}",
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
        @DisplayName("OR: один ложный, другой истинный → SUCCESS")
        void orWithOneTrueSucceeds() {
            Long sequenceId = createSequenceWithSteps("COMPOUND OR", List.of(
                    new StepCreateRequest(
                            "ON OR INIT",
                            StepType.EVALUATE,
                            "{\"type\":\"COMPOUND\",\"operator\":\"OR\",\"children\":["
                                    + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"},"
                                    + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"}"
                                    + "]}",
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
        @DisplayName("AND: один ложный → FAILURE (onFailure=ABORT)")
        void andWithOneFalseFails() {
            Long sequenceId = createSequenceWithSteps("COMPOUND AND fails", List.of(
                    new StepCreateRequest(
                            "INIT AND ON (невозможно одновременно)",
                            StepType.EVALUATE,
                            "{\"type\":\"COMPOUND\",\"operator\":\"AND\",\"children\":["
                                    + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"INIT\"},"
                                    + "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"ON\"}"
                                    + "]}",
                            null,
                            TransitionAction.END, null, false,
                            TransitionAction.ABORT, null, false
                    )
            ));

            executionService.startExecution(sequenceId, AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(sequenceId, AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
        }
    }
}
