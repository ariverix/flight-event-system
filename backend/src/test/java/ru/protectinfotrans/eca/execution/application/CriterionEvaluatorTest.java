package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты оценщика критериев.
 * Проверяет все 6 типов критериев + COMPOUND.
 *
 * См. диплом: раздел 1.2.2 (Sequencer Criteria), Глава 3 (Тестирование)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CriterionEvaluator")
class CriterionEvaluatorTest {

    @Mock
    private MessageRepositoryPort messageRepository;

    @Spy
    private ObjectMapper objectMapper;

    @InjectMocks
    private CriterionEvaluator evaluator;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> additionalData = new HashMap<>();
        additionalData.put("etdTime", LocalDateTime.of(2024, 1, 10, 10, 0));
        additionalData.put("offTime", LocalDateTime.of(2024, 1, 10, 10, 30));

        context = new ExecutionContext(
                "VP-BAB",
                "SU1234",
                FlightStage.OFF,
                LocalDateTime.of(2024, 1, 10, 10, 35),
                additionalData
        );
    }

    @Nested
    @DisplayName("MESSAGE_RECEIVED критерий")
    class MessageReceivedTests {

        @Test
        @DisplayName("должен вернуть true если сообщение получено")
        void shouldReturnTrueWhenMessageReceived() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "DOWNLINK",
                    "templateName": "STATUS",
                    "fromThisPointOnly": false
                }
                """;

            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BAB"), eq(MessageType.DOWNLINK), eq("STATUS"), any()
            )).thenReturn(true);

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("должен учитывать fromThisPointOnly для WAIT-шагов")
        void shouldRespectFromThisPointOnlyForWait() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "UPLINK",
                    "templateName": "CLEARANCE",
                    "fromThisPointOnly": true
                }
                """;

            LocalDateTime waitStarted = LocalDateTime.of(2024, 1, 10, 10, 20);

            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BAB"), eq(MessageType.UPLINK), eq("CLEARANCE"), eq(waitStarted)
            )).thenReturn(true);

            boolean result = evaluator.evaluate(criteria, context, waitStarted);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("FLIGHT_STAGE критерий")
    class FlightStageTests {

        @Test
        @DisplayName("EQUALS: должен вернуть true если стадия совпадает")
        void shouldReturnTrueForEquals() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "EQUALS",
                    "targetStage": "OFF"
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("GREATER: должен вернуть true если текущая стадия больше")
        void shouldReturnTrueForGreater() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "GREATER",
                    "targetStage": "OUT"
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("NOT_EQUAL: должен вернуть true если стадия не совпадает")
        void shouldReturnTrueForNotEqual() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "NOT_EQUAL",
                    "targetStage": "INIT"
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("POSITION_REPORTED критерий")
    class PositionReportedTests {

        @Test
        @DisplayName("должен вернуть true если позиционный отчёт получен за последние N минут")
        void shouldReturnTrueWhenPositionReported() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsPositionReportWithinMinutes("VP-BAB", 30))
                    .thenReturn(true);

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("TIME_COMPARISON критерий")
    class TimeComparisonTests {

        @Test
        @DisplayName("IS_AFTER: должен вернуть true если текущее время после ETD + offset")
        void shouldReturnTrueForIsAfter() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_AFTER",
                    "referencePoint": "ETD",
                    "offsetMinutes": 10
                }
                """;

            // currentTime = 10:35, ETD = 10:00, ETD + 10 = 10:10 → 10:35 > 10:10 = true

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("IS_BEFORE: должен вернуть true если текущее время до Off + offset")
        void shouldReturnTrueForIsBefore() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_BEFORE",
                    "referencePoint": "Off",
                    "offsetMinutes": 60
                }
                """;

            // currentTime = 10:35, Off = 10:30, Off + 60 = 11:30 → 10:35 < 11:30 = true

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("CONDITION_ACTIVE критерий")
    class ConditionActiveTests {

        @Test
        @DisplayName("должен вернуть true если пользовательское условие активно")
        void shouldReturnTrueWhenConditionActive() {
            String criteria = """
                {
                    "type": "CONDITION_ACTIVE",
                    "conditionName": "DELAYED"
                }
                """;

            Map<String, Boolean> activeConditions = Map.of("DELAYED", true);
            Map<String, Object> additionalData = new HashMap<>(context.additionalData());
            additionalData.put("activeConditions", activeConditions);

            ExecutionContext contextWithConditions = new ExecutionContext(
                    context.aircraftId(),
                    context.flightNumber(),
                    context.currentFlightStage(),
                    context.currentTime(),
                    additionalData
            );

            boolean result = evaluator.evaluate(criteria, contextWithConditions, null);

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("COMPOUND критерий")
    class CompoundTests {

        @Test
        @DisplayName("AND: должен вернуть true если все дочерние критерии true")
        void shouldReturnTrueForAndWhenAllChildrenTrue() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "AND",
                    "children": [
                        {
                            "type": "FLIGHT_STAGE",
                            "operator": "EQUALS",
                            "targetStage": "OFF"
                        },
                        {
                            "type": "TIME_COMPARISON",
                            "operator": "IS_AFTER",
                            "referencePoint": "ETD",
                            "offsetMinutes": 0
                        }
                    ]
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("OR: должен вернуть true если хотя бы один дочерний критерий true")
        void shouldReturnTrueForOrWhenAnyChildTrue() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "OR",
                    "children": [
                        {
                            "type": "FLIGHT_STAGE",
                            "operator": "EQUALS",
                            "targetStage": "INIT"
                        },
                        {
                            "type": "FLIGHT_STAGE",
                            "operator": "EQUALS",
                            "targetStage": "OFF"
                        }
                    ]
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("AND: должен вернуть false если хотя бы один дочерний критерий false")
        void shouldReturnFalseForAndWhenAnyChildFalse() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "AND",
                    "children": [
                        {
                            "type": "FLIGHT_STAGE",
                            "operator": "EQUALS",
                            "targetStage": "OFF"
                        },
                        {
                            "type": "FLIGHT_STAGE",
                            "operator": "EQUALS",
                            "targetStage": "IN"
                        }
                    ]
                }
                """;

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isFalse();
        }
    }

    @Test
    @DisplayName("должен вернуть false для пустого или null критерия")
    void shouldReturnFalseForEmptyOrNullCriteria() {
        assertThat(evaluator.evaluate(null, context, null)).isFalse();
        assertThat(evaluator.evaluate("", context, null)).isFalse();
        assertThat(evaluator.evaluate("   ", context, null)).isFalse();
    }
}
