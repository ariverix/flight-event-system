package ru.protectinfotrans.eca.conditions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.conditions.domain.ConditionAlreadyRaisedException;
import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.conditions.dto.RaisedConditionResponse;
import ru.protectinfotrans.eca.conditions.port.in.ConditionManagementUseCase;
import ru.protectinfotrans.eca.conditions.port.in.ConditionQueryUseCase;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3-3: сквозной сценарий движка условий/алертов — паритет с SITA Sequencer.
 *
 * <p>Покрывает Definition of Done задачи на реальном Postgres (не mock-репозиторий):
 * <ol>
 *   <li>raise condition -> критерий CONDITION_ACTIVE видит условие как true;</li>
 *   <li>повторный raise того же имени на ещё активном условии -> ошибка (не no-op);</li>
 *   <li>close condition -> CONDITION_ACTIVE становится false;</li>
 *   <li>уровни алертов независимы от факта активности условия;</li>
 *   <li>авто-закрытие активных условий рейса на терминальных стадиях (IN/SUMMARY);</li>
 *   <li>per-flight изоляция — разные рейсы одного борта не делят условия;</li>
 *   <li>ACTION-шаги RAISE_CONDITION/CLOSE_CONDITION движка работают через тот же сквозной путь.</li>
 * </ol>
 *
 * Демосценарий: борт VP-BQR (CLAUDE.md), рейсы SU1234/SU5678.
 */
@DisplayName("P3-3: движок условий/алертов — raise/close, уровни, CONDITION_ACTIVE, авто-закрытие")
class P3_3_ConditionsEngineScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private ConditionManagementUseCase conditionManagementUseCase;

    @Autowired
    private ConditionQueryUseCase conditionQueryUseCase;

    @Autowired
    private MessageInputPort messageInputPort;

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

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
    // 1. raise/close -> CONDITION_ACTIVE
    // ============================================================
    @Nested
    @DisplayName("raise/close condition -> критерий CONDITION_ACTIVE")
    class RaiseCloseConditionActiveTests {

        @Test
        @DisplayName("raiseCondition -> isConditionActive=true и видно в getActiveConditions с уровнем алерта")
        void raiseConditionMakesItActiveWithAlertLevel() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "NO_POSITION", AlertLevel.HIGH);

            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "NO_POSITION")).isTrue();
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("NO_POSITION", AlertLevel.HIGH);
        }

        @Test
        @DisplayName("EVALUATE IF с CONDITION_ACTIVE мгновенно видит ранее поднятое условие через движок")
        void evaluateConditionActiveCriterionSeesRaisedCondition() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.MEDIUM);

            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-3 CONDITION_ACTIVE Demo", null, null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Проверить условие DELAYED",
                    StepType.EVALUATE,
                    "{\"type\":\"CONDITION_ACTIVE\",\"conditionName\":\"DELAYED\"}",
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(created.id(), AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("closeCondition -> isConditionActive становится false")
        void closeConditionMakesItInactive() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.LOW);
            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED")).isTrue();

            conditionManagementUseCase.closeCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED");

            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED")).isFalse();
        }

        @Test
        @DisplayName("повторный raise того же имени, пока условие активно -> ConditionAlreadyRaisedException, "
                + "первый подъём остаётся активным с НЕИЗМЕНЁННЫМ уровнем")
        void duplicateRaiseIsRejectedAndDoesNotMutateExisting() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.LOW);

            assertThatThrownBy(() ->
                    conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.CRITICAL))
                    .isInstanceOf(ConditionAlreadyRaisedException.class);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("DELAYED", AlertLevel.LOW);
        }

        @Test
        @DisplayName("close затем повторный raise того же имени -> разрешён (новый подъём с новым уровнем)")
        void reRaiseAfterCloseIsAllowed() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.LOW);
            conditionManagementUseCase.closeCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED");

            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.CRITICAL);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("DELAYED", AlertLevel.CRITICAL);
        }
    }

    // ============================================================
    // 2. Условие и уровень алерта независимы
    // ============================================================
    @Nested
    @DisplayName("Условие и уровень алерта — независимые сущности")
    class AlertLevelIndependenceTests {

        @Test
        @DisplayName("условие можно поднять с уровнем NO — CONDITION_ACTIVE всё равно true (нет алертинга, но условие активно)")
        void conditionActiveRegardlessOfNoAlertLevel() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "SILENT_TRACKING", AlertLevel.NO);

            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "SILENT_TRACKING")).isTrue();
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("SILENT_TRACKING", AlertLevel.NO);
        }

        @Test
        @DisplayName("все 5 уровней (No/Low/Medium/High/Critical) поддержаны как атрибут подъёма")
        void allFiveAlertLevelsSupported() {
            for (AlertLevel level : AlertLevel.values()) {
                String name = "COND_" + level.name();
                conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, name, level);
                assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                        .containsEntry(name, level);
            }
        }
    }

    // ============================================================
    // 3. ACTION RAISE_CONDITION/CLOSE_CONDITION через движок
    // ============================================================
    @Nested
    @DisplayName("ACTION-шаги RAISE_CONDITION/CLOSE_CONDITION")
    class ActionStepTests {

        @Test
        @DisplayName("ACTION RAISE_CONDITION выполняется и реально поднимает условие через движок условий")
        void actionRaiseConditionStepRaisesRealCondition() {
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-3 ACTION RAISE_CONDITION Demo", null, null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Поднять условие ENGINE_FAILURE",
                    StepType.ACTION,
                    "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"ENGINE_FAILURE\",\"alertLevel\":\"CRITICAL\"}",
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.END, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(created.id(), AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("ENGINE_FAILURE", AlertLevel.CRITICAL);
        }

        @Test
        @DisplayName("ACTION RAISE_CONDITION с нестандартным alertLevel завершается FAILURE и не поднимает условие")
        void actionRaiseConditionStepFailsOnNonCanonicalAlertLevel() {
            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-3 ACTION RAISE_CONDITION Bad Level Demo", null, null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Поднять условие с кривым уровнем",
                    StepType.ACTION,
                    "{\"actionType\":\"RAISE_CONDITION\",\"conditionName\":\"LEGACY\",\"alertLevel\":\"WARNING\"}",
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            ExecutionInstance instance = findInstance(created.id(), AIRCRAFT_ID);
            assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.ABORTED);
            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "LEGACY")).isFalse();
        }
    }

    // ============================================================
    // 4. Авто-закрытие при завершении рейса
    // ============================================================
    @Nested
    @DisplayName("Авто-закрытие активных условий при завершении рейса")
    class AutoCloseTests {

        @Test
        @DisplayName("стадия IN -> все активные условия рейса закрываются, CONDITION_ACTIVE становится false")
        void stageInAutoClosesActiveConditions() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "NO_POSITION", AlertLevel.MEDIUM);
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER)).hasSize(2);

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED")).isFalse();
            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "NO_POSITION")).isFalse();
        }

        @Test
        @DisplayName("стадия SUMMARY также авто-закрывает активные условия рейса")
        void stageSummaryAutoClosesActiveConditions() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.SUMMARY);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
        }

        @Test
        @DisplayName("после авто-закрытия на IN условие с тем же именем можно поднять заново на том же рейсе")
        void canReRaiseSameNameAfterAutoCloseOnSameFlight() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.LOW);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("DELAYED", AlertLevel.LOW);
        }

        @Test
        @DisplayName("нетерминальные стадии (OUT/OFF/ON) НЕ закрывают активные условия рейса")
        void nonTerminalStagesDoNotAutoClose() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.OUT);
            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.OFF);
            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.ON);

            assertThat(conditionQueryUseCase.isConditionActive(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED")).isTrue();
        }
    }

    // ============================================================
    // 5. Per-flight изоляция
    // ============================================================
    @Nested
    @DisplayName("Per-flight изоляция условий")
    class PerFlightIsolationTests {

        @Test
        @DisplayName("разные рейсы одного борта изолированы: условие одного рейса не видно на другом")
        void differentFlightsOfSameAircraftAreIsolated() {
            String otherFlight = "SU5678";

            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, otherFlight, "DELAYED", AlertLevel.LOW);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("DELAYED", AlertLevel.HIGH);
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, otherFlight))
                    .containsEntry("DELAYED", AlertLevel.LOW);
        }

        @Test
        @DisplayName("авто-закрытие на завершении ОДНОГО рейса не закрывает условия ДРУГОГО рейса того же борта "
                + "(условие не \"течёт\" между рейсами одного борта — фикс регрессии старой per-aircraft модели)")
        void autoCloseOfOneFlightDoesNotAffectAnotherFlightOfSameAircraft() {
            String otherFlight = "SU5678";

            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, otherFlight, "DELAYED", AlertLevel.LOW);

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
            assertThat(conditionQueryUseCase.getActiveConditions(AIRCRAFT_ID, otherFlight))
                    .containsEntry("DELAYED", AlertLevel.LOW);
        }

        @Test
        @DisplayName("разные борта полностью изолированы друг от друга")
        void differentAircraftAreIsolated() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);

            assertThat(conditionQueryUseCase.isConditionActive("VP-OTHER", FLIGHT_NUMBER, "DELAYED")).isFalse();
        }
    }

    // ============================================================
    // 6. Операторский обзор (listAllActive)
    // ============================================================
    @Nested
    @DisplayName("Операторский обзор активных условий")
    class ListAllActiveTests {

        @Test
        @DisplayName("listAllActive -> возвращает все активные условия по всем бортам/рейсам, закрытые не включены")
        void listAllActiveReturnsOnlyOpenConditionsAcrossFleet() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            conditionManagementUseCase.raiseCondition("VP-OTHER", "SU9999", "NO_POSITION", AlertLevel.LOW);
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "TEMP", AlertLevel.MEDIUM);
            conditionManagementUseCase.closeCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "TEMP");

            List<RaisedCondition> active = conditionQueryUseCase.listAllActive();

            assertThat(active).extracting(RaisedCondition::getConditionName)
                    .contains("DELAYED", "NO_POSITION")
                    .doesNotContain("TEMP");
        }
    }

    // ============================================================
    // 7. Операторский RBAC-эндпоинт GET /api/v1/conditions
    // ============================================================
    @Nested
    @DisplayName("GET /api/v1/conditions — RBAC")
    class ConditionControllerRbacTests {

        @Test
        @DisplayName("с валидным JWT (admin) -> 200 и список активных условий")
        void listActiveConditionsWithValidToken() {
            conditionManagementUseCase.raiseCondition(AIRCRAFT_ID, FLIGHT_NUMBER, "DELAYED", AlertLevel.HIGH);
            String token = getAdminToken();

            org.springframework.http.ResponseEntity<RaisedConditionResponse[]> response = restTemplate.exchange(
                    "/api/v1/conditions",
                    org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(authHeaders(token)),
                    RaisedConditionResponse[].class);

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(java.util.Arrays.stream(response.getBody())
                    .anyMatch(m -> "DELAYED".equals(m.conditionName()))).isTrue();
        }

        @Test
        @DisplayName("без JWT -> 401 (RBAC-правило ДО catch-all permitAll)")
        void listActiveConditionsWithoutTokenIsRejected() {
            org.springframework.http.ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/v1/conditions", String.class);

            assertThat(response.getStatusCode().value()).isEqualTo(401);
        }
    }
}
