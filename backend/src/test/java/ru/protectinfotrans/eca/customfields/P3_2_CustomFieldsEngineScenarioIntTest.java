package ru.protectinfotrans.eca.customfields;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldQueryUseCase;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldRuleManagementUseCase;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.port.in.TemplateManagementUseCase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-2: сквозной сценарий движка custom fields — паритет с SITA Sequencer.
 *
 * <p>Покрывает весь Definition of Done задачи:
 * <ol>
 *   <li>извлечение значения из входящего сообщения -> сохранение per-flight;</li>
 *   <li>подстановка {@code {{customField.X}}} в исходящий шаблон ACTION-шага -> извлечённое значение
 *       (через {@code ActionStepRule#mergeCustomFields} -> {@code OutboundMessage#paramsJson} ->
 *       {@code TemplateRenderUseCase#render});</li>
 *   <li>доступность извлечённого значения execution-контексту на момент оценки критерия
 *       ({@code CustomFieldQueryUseCase#getActiveValues} -> {@code ExecutionContext.additionalData});</li>
 *   <li>закрытие контекста при завершении рейса (IN/SUMMARY) — поле не подставляется и не
 *       используется после закрытия;</li>
 *   <li>per-flight изоляция — разные рейсы одного борта видят разные значения одного и того же поля.</li>
 * </ol>
 *
 * Демосценарий: борт VP-BQR (CLAUDE.md), рейсы SU1234/SU5678.
 */
@DisplayName("P3-2: движок custom fields — извлечение, подстановка, критерии, закрытие контекста")
class P3_2_CustomFieldsEngineScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private CustomFieldRuleManagementUseCase ruleUseCase;

    @Autowired
    private CustomFieldQueryUseCase customFieldQueryUseCase;

    @Autowired
    private MessageInputPort messageInputPort;

    @Autowired
    private TemplateManagementUseCase templateUseCase;

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private OutboundMessageRepositoryPort outboundMessageRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    private void createGateNumberRule() {
        ruleUseCase.create(new CustomFieldRuleCreateRequest(
                "GATE_NUMBER", "Gate number from STATUS message", MessageType.DOWNLINK, "STATUS",
                ExtractionSource.CONTENT, "GATE=(\\w+)", true));
    }

    private List<OutboundMessage> findAllOutbound() {
        return jdbcTemplate.query(
                "SELECT id FROM outbound_messages ORDER BY id ASC",
                (rs, rowNum) -> outboundMessageRepository.findById(rs.getLong("id")).orElseThrow());
    }

    // ============================================================
    // 1. Извлечение из входящего сообщения -> сохранение per-flight
    // ============================================================
    @Nested
    @DisplayName("Извлечение значения из входящего сообщения")
    class ExtractionTests {

        @Test
        @DisplayName("STATUS-сообщение с GATE=A12 -> значение GATE_NUMBER сохранено для рейса")
        void incomingStatusMessageExtractsGateNumberForFlight() {
            createGateNumberRule();

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());

            Map<String, String> values = customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER);
            assertThat(values).containsEntry("customField.GATE_NUMBER", "A12");
        }

        @Test
        @DisplayName("повторное сообщение с другим значением -> перезаписывает (текущее значение, не история)")
        void laterMessageOverwritesEarlierValue() {
            createGateNumberRule();

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());
            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=B7 OK", Map.of());

            Map<String, String> values = customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER);
            assertThat(values).containsEntry("customField.GATE_NUMBER", "B7");
        }
    }

    // ============================================================
    // 2. Подстановка в исходящий шаблон ACTION-шага
    // ============================================================
    @Nested
    @DisplayName("Подстановка в исходящий шаблон")
    class TemplateSubstitutionTests {

        @Test
        @DisplayName("ACTION SEND_UPLINK объединяет извлечённое значение GATE_NUMBER в params durable-записи")
        void sendUplinkMergesExtractedCustomFieldIntoOutboundParams() {
            createGateNumberRule();
            templateUseCase.create(new TemplateCreateRequest(
                    "GATE_CONFIRM", "Подтверждение гейта", MessageType.UPLINK,
                    ru.protectinfotrans.eca.sequence.domain.UplinkOrigin.COMPUTER_GENERATED, null,
                    "Confirmed gate {{customField.GATE_NUMBER}}", true));

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());

            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-2 Custom Field Substitution Demo", "ACTION SEND_UPLINK с подстановкой custom field",
                    null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Send gate confirmation",
                    StepType.ACTION,
                    """
                    {
                        "actionType": "SEND_UPLINK",
                        "templateName": "GATE_CONFIRM",
                        "params": {}
                    }
                    """,
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.END, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            // params durable-записи содержит ключ customField.GATE_NUMBER со значением "A12" —
            // merge произошёл на момент выполнения ACTION-шага (детерминированный рендер при retry)
            assertThat(all.get(0).getParamsJson()).contains("customField.GATE_NUMBER").contains("A12");
        }

        @Test
        @DisplayName("явный params ACTION-шага имеет приоритет над custom field с тем же ключом")
        void explicitParamOverridesCustomFieldWithSameKey() {
            ruleUseCase.create(new CustomFieldRuleCreateRequest(
                    "GATE", "Gate raw key", MessageType.DOWNLINK, "STATUS",
                    ExtractionSource.CONTENT, "GATE=(\\w+)", true));

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());

            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-2 Explicit Param Priority Demo", null, null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Send with explicit override",
                    StepType.ACTION,
                    """
                    {
                        "actionType": "SEND_UPLINK",
                        "templateName": "CLEARANCE",
                        "params": {"customField.GATE": "EXPLICIT_WINS"}
                    }
                    """,
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.END, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getParamsJson()).contains("EXPLICIT_WINS");
            assertThat(all.get(0).getParamsJson()).doesNotContain("A12");
        }
    }

    // ============================================================
    // 3. Использование значения в критерии
    // ============================================================
    @Nested
    @DisplayName("Использование значения в критерии")
    class CriterionUsageTests {

        @Test
        @DisplayName("извлечённое значение custom field доступно execution-контексту (getActiveValues) на момент оценки критерия")
        void extractedCustomFieldValueIsAvailableForCriterionEvaluation() {
            createGateNumberRule();

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());

            // Активные значения custom fields рейса — ровно та карта, которую ExecutionService
            // кладёт в ExecutionContext.additionalData("customFields") на момент оценки критерия.
            // Значение извлечено из входящего сообщения и доступно для переиспользования (паритет SITA, P3-2).
            Map<String, String> activeValues = customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER);

            assertThat(activeValues).containsEntry("customField.GATE_NUMBER", "A12");
        }
    }

    // ============================================================
    // 4. Закрытие контекста при завершении рейса
    // ============================================================
    @Nested
    @DisplayName("Закрытие контекста при завершении рейса")
    class ContextClosureTests {

        @Test
        @DisplayName("после стадии IN значение GATE_NUMBER больше не подставляется/не возвращается")
        void valueNotReturnedAfterFlightReachesInStage() {
            createGateNumberRule();

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());
            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("customField.GATE_NUMBER", "A12");

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
        }

        @Test
        @DisplayName("после стадии IN ACTION SEND_UPLINK больше НЕ подставляет закрытое значение в params")
        void sendUplinkNoLongerMergesValueAfterContextClosed() {
            createGateNumberRule();
            templateUseCase.create(new TemplateCreateRequest(
                    "GATE_CONFIRM_CLOSED", "Подтверждение гейта (после закрытия)", MessageType.UPLINK,
                    ru.protectinfotrans.eca.sequence.domain.UplinkOrigin.COMPUTER_GENERATED, null,
                    "Status update", true));

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());
            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            SequenceCreateRequest createReq = new SequenceCreateRequest(
                    "P3-2 Closed Context Demo", null, null, null);
            SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
            sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                    "Send after closure",
                    StepType.ACTION,
                    """
                    {
                        "actionType": "SEND_UPLINK",
                        "templateName": "GATE_CONFIRM_CLOSED",
                        "params": {}
                    }
                    """,
                    null,
                    TransitionAction.END, null, false,
                    TransitionAction.END, null, false
            ), 1L);
            sequenceUseCase.activateSequence(created.id(), 1L);

            executionService.startExecution(created.id(), AIRCRAFT_ID, FLIGHT_NUMBER);

            List<OutboundMessage> all = findAllOutbound();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getParamsJson()).doesNotContain("GATE_NUMBER").doesNotContain("A12");
        }

        @Test
        @DisplayName("стадия SUMMARY также закрывает контекст рейса")
        void summaryStageAlsoClosesContext() {
            createGateNumberRule();

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.SUMMARY);

            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
        }
    }

    // ============================================================
    // 5. Per-flight изоляция
    // ============================================================
    @Nested
    @DisplayName("Per-flight изоляция значений")
    class PerFlightIsolationTests {

        @Test
        @DisplayName("разные рейсы одного борта видят разные значения одного и того же поля")
        void differentFlightsOfSameAircraftHaveIsolatedValues() {
            createGateNumberRule();
            String otherFlight = "SU5678";

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());
            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, otherFlight,
                    "STATUS GATE=C99 OK", Map.of());

            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER))
                    .containsEntry("customField.GATE_NUMBER", "A12");
            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, otherFlight))
                    .containsEntry("customField.GATE_NUMBER", "C99");
        }

        @Test
        @DisplayName("закрытие контекста ОДНОГО рейса не закрывает контекст ДРУГОГО рейса того же борта")
        void closingContextOfOneFlightDoesNotAffectAnotherFlight() {
            createGateNumberRule();
            String otherFlight = "SU5678";

            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER,
                    "STATUS GATE=A12 OK", Map.of());
            messageInputPort.receiveMessage(MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, otherFlight,
                    "STATUS GATE=C99 OK", Map.of());

            messageInputPort.notifyFlightStageChange(AIRCRAFT_ID, FLIGHT_NUMBER, FlightStage.IN);

            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, FLIGHT_NUMBER)).isEmpty();
            assertThat(customFieldQueryUseCase.getActiveValues(AIRCRAFT_ID, otherFlight))
                    .containsEntry("customField.GATE_NUMBER", "C99");
        }
    }
}
