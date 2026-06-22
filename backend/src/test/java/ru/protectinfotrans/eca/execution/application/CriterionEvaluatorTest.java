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
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты оценщика критериев — паритет с SITA Sequencer:
 * message received, flight stage, position, time, плюс CONDITION_ACTIVE и COMPOUND (AND/OR).
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
        additionalData.put("etaTime", LocalDateTime.of(2024, 1, 10, 13, 0));
        additionalData.put("initTime", LocalDateTime.of(2024, 1, 10, 9, 0));
        additionalData.put("outTime", LocalDateTime.of(2024, 1, 10, 10, 15));
        additionalData.put("offTime", LocalDateTime.of(2024, 1, 10, 10, 30));
        additionalData.put("onTime", LocalDateTime.of(2024, 1, 10, 12, 45));
        additionalData.put("inTime", LocalDateTime.of(2024, 1, 10, 13, 5));

        context = new ExecutionContext(
                "VP-BQR",
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
        @DisplayName("должен вернуть true если сообщение получено (downlink)")
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
                    eq("VP-BQR"), eq(MessageType.DOWNLINK), eq("STATUS"), any()
            )).thenReturn(true);

            boolean result = evaluator.evaluate(criteria, context, null);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать uplink-сообщения")
        void shouldSupportUplinkMessageType() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "UPLINK",
                    "templateName": "REQUEST_POSITION"
                }
                """;

            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BQR"), eq(MessageType.UPLINK), eq("REQUEST_POSITION"), any()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать ground-сообщения")
        void shouldSupportGroundMessageType() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "GROUND",
                    "templateName": "DISPATCH_NOTIFY"
                }
                """;

            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BQR"), eq(MessageType.GROUND), eq("DISPATCH_NOTIFY"), any()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
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
                    eq("VP-BQR"), eq(MessageType.UPLINK), eq("CLEARANCE"), eq(waitStarted)
            )).thenReturn(true);

            boolean result = evaluator.evaluate(criteria, context, waitStarted);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("fromThisPointOnly=true должен отсечь историю — старое сообщение игнорируется")
        void shouldIgnoreHistoricalMessageWhenFromThisPointOnly() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "DOWNLINK",
                    "templateName": "POSITION_REPORT",
                    "fromThisPointOnly": true
                }
                """;

            LocalDateTime waitStarted = LocalDateTime.of(2024, 1, 10, 10, 30);

            // repository возвращает false для запроса "после waitStarted" —
            // даже если историческое сообщение существовало раньше этой точки
            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BQR"), eq(MessageType.DOWNLINK), eq("POSITION_REPORT"), eq(waitStarted)
            )).thenReturn(false);

            boolean result = evaluator.evaluate(criteria, context, waitStarted);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("fromThisPointOnly=false должен учитывать всю историю (afterTime=null)")
        void shouldIgnoreWaitStartedWhenFromThisPointOnlyFalse() {
            String criteria = """
                {
                    "type": "MESSAGE_RECEIVED",
                    "messageType": "DOWNLINK",
                    "templateName": "POSITION_REPORT",
                    "fromThisPointOnly": false
                }
                """;

            LocalDateTime waitStarted = LocalDateTime.of(2024, 1, 10, 10, 30);

            when(messageRepository.existsByAircraftAndTypeAndTemplate(
                    eq("VP-BQR"), eq(MessageType.DOWNLINK), eq("POSITION_REPORT"), isNull()
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

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("GREATER_THAN: должен вернуть true если текущая стадия позже целевой")
        void shouldReturnTrueForGreaterThan() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "GREATER_THAN",
                    "targetStage": "OUT"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("LESS_THAN: должен вернуть true если текущая стадия раньше целевой")
        void shouldReturnTrueForLessThan() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "LESS_THAN",
                    "targetStage": "ON"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("GREATER_OR_EQUAL: должен вернуть true для точного совпадения")
        void shouldReturnTrueForGreaterOrEqualOnExactMatch() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "GREATER_OR_EQUAL",
                    "targetStage": "OFF"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("LESS_OR_EQUAL: должен вернуть true для точного совпадения")
        void shouldReturnTrueForLessOrEqualOnExactMatch() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "LESS_OR_EQUAL",
                    "targetStage": "OFF"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("NOT_EQUALS: должен вернуть true если стадия не совпадает")
        void shouldReturnTrueForNotEqual() {
            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "NOT_EQUALS",
                    "targetStage": "INIT"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать стадию SUMMARY (паритет с SITA: Init/Out/Off/On/In/Summary)")
        void shouldSupportSummaryStage() {
            ExecutionContext summaryContext = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(),
                    FlightStage.SUMMARY, context.currentTime(), context.additionalData()
            );

            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "EQUALS",
                    "targetStage": "SUMMARY"
                }
                """;

            assertThat(evaluator.evaluate(criteria, summaryContext, null)).isTrue();

            String greaterThanIn = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "GREATER_THAN",
                    "targetStage": "IN"
                }
                """;
            assertThat(evaluator.evaluate(greaterThanIn, summaryContext, null)).isTrue();
        }

        @Test
        @DisplayName("должен вернуть false если текущая стадия неизвестна (null)")
        void shouldReturnFalseWhenCurrentStageIsNull() {
            ExecutionContext noStageContext = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), null, context.currentTime(), context.additionalData()
            );

            String criteria = """
                {
                    "type": "FLIGHT_STAGE",
                    "operator": "EQUALS",
                    "targetStage": "OFF"
                }
                """;

            assertThat(evaluator.evaluate(criteria, noStageContext, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("POSITION_REPORTED критерий")
    class PositionReportedTests {

        @Test
        @DisplayName("reported (default): должен вернуть true если фактический отчёт получен за окно")
        void shouldReturnTrueWhenActualPositionReported() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), isNull(), isNull()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("not reported: должен вернуть true если фактического отчёта НЕ было за окно")
        void shouldReturnTrueForNotReportedWhenNoActualReport() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "reported": false,
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), isNull(), isNull()
            )).thenReturn(false);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("not reported: должен вернуть false если фактический отчёт всё-таки был")
        void shouldReturnFalseForNotReportedWhenActualReportExists() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "reported": false,
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), isNull(), isNull()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
        }

        @Test
        @DisplayName("должен фильтровать по источнику ACARS")
        void shouldFilterBySourceAcars() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 15,
                    "source": "ACARS"
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), eq(PositionSource.ACARS), isNull()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен фильтровать по источнику RADAR")
        void shouldFilterBySourceRadar() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 15,
                    "source": "RADAR"
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), eq(PositionSource.RADAR), isNull()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен фильтровать по источнику ADS_B")
        void shouldFilterBySourceAdsB() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 15,
                    "source": "ADS_B"
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), eq(PositionSource.ADS_B), isNull()
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("fromThisPointOnly=true должен передать waitStartedAt как afterTime (отсекая историю)")
        void shouldRespectFromThisPointOnlyForWait() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 30,
                    "fromThisPointOnly": true
                }
                """;

            LocalDateTime waitStarted = LocalDateTime.of(2024, 1, 10, 10, 20);

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), isNull(), eq(waitStarted)
            )).thenReturn(true);

            assertThat(evaluator.evaluate(criteria, context, waitStarted)).isTrue();
        }

        @Test
        @DisplayName("без minutesAgo должен вернуть false (некорректная конфигурация)")
        void shouldReturnFalseWhenMinutesAgoMissing() {
            String criteria = """
                {
                    "type": "POSITION_REPORTED"
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
        }

        @Test
        @DisplayName("P2-5: not reported должен ограничить нижнюю границу окна Off-таймстампом, " +
                "если запрошенное окно длиннее времени с момента Off")
        void notReportedShouldClampWindowToOffTimestamp() {
            // context: currentTime=10:35, offTime=10:30 (5 минут с момента Off).
            // Запрошено minutesAgo=30 -> naive sinceTime было бы 10:05, ДО взлёта;
            // эффективная нижняя граница должна быть max(10:05, 10:30) = 10:30 (момент Off), не 10:05.
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "reported": false,
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), eq(LocalDateTime.of(2024, 1, 10, 10, 30)), isNull(), isNull()
            )).thenReturn(false);

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("P2-5: not reported должен использовать запрошенное окно as-is, если оно короче времени с Off")
        void notReportedShouldUseRequestedWindowWhenShorterThanTimeSinceOff() {
            // offTime=10:30, currentTime=11:00 (контекст с currentTime позже стандартного,
            // конструируем явно) -> 30 минут с момента Off. minutesAgo=10 -> sinceTime=10:50,
            // ПОЗЖЕ offTime -> используется запрошенное окно (10:50), не Off (10:30).
            ExecutionContext laterContext = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), context.currentFlightStage(),
                    LocalDateTime.of(2024, 1, 10, 11, 0), context.additionalData()
            );

            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "reported": false,
                    "minutesAgo": 10
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), eq(LocalDateTime.of(2024, 1, 10, 10, 50)), isNull(), isNull()
            )).thenReturn(false);

            assertThat(evaluator.evaluate(criteria, laterContext, null)).isTrue();
        }

        @Test
        @DisplayName("P2-5: not reported должен вернуть false (не применимо), если Off-таймстамп неизвестен")
        void notReportedShouldReturnFalseWhenOffTimeUnknown() {
            Map<String, Object> noOffData = new HashMap<>(context.additionalData());
            noOffData.remove("offTime");
            ExecutionContext noOffContext = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), context.currentFlightStage(),
                    context.currentTime(), noOffData
            );

            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "reported": false,
                    "minutesAgo": 30
                }
                """;

            boolean result = evaluator.evaluate(criteria, noOffContext, null);

            assertThat(result).isFalse();
            // не должен даже обращаться к репозиторию — проверка неприменима без Off-таймстампа
            org.mockito.Mockito.verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("оценочные (estimated) позиции игнорируются — делегируется в репозиторий, " +
                "который сам исключает estimated=true из выборки")
        void shouldDelegateEstimatedExclusionToRepository() {
            // Сам критерий не содержит флага estimated — игнорирование estimated-позиций
            // реализовано на уровне запроса (MessageRepositoryPort.existsActualPositionReportSince
            // фильтрует estimatedPosition=false). Здесь проверяем, что критерий действительно
            // обращается именно к "actual"-методу порта, а не к историческому "любая позиция".
            String criteria = """
                {
                    "type": "POSITION_REPORTED",
                    "minutesAgo": 30
                }
                """;

            when(messageRepository.existsActualPositionReportSince(
                    eq("VP-BQR"), any(LocalDateTime.class), isNull(), isNull()
            )).thenReturn(false);

            // Если бы оценочная позиция учитывалась, репозиторий вернул бы true для "есть отчёт";
            // здесь явный false показывает, что критерий доверяет результату репозитория без хвоста.
            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
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
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("IS_BEFORE: должен вернуть true если текущее время до Off + offset")
        void shouldReturnTrueForIsBefore() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_BEFORE",
                    "referencePoint": "OFF",
                    "offsetMinutes": 60
                }
                """;

            // currentTime = 10:35, Off = 10:30, Off + 60 = 11:30 → 10:35 < 11:30 = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("IS_EQUAL: должен вернуть true при точном совпадении времени")
        void shouldReturnTrueForIsEqual() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_EQUAL",
                    "referencePoint": "OFF",
                    "offsetMinutes": 5
                }
                """;

            // currentTime = 10:35, Off + 5 = 10:35 → точное совпадение
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать referencePoint=ETA")
        void shouldSupportEtaReferencePoint() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_BEFORE",
                    "referencePoint": "ETA",
                    "offsetMinutes": 0
                }
                """;

            // currentTime = 10:35, ETA = 13:00 → before = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать referencePoint=INIT")
        void shouldSupportInitReferencePoint() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_AFTER",
                    "referencePoint": "INIT",
                    "offsetMinutes": 0
                }
                """;

            // currentTime = 10:35, Init = 09:00 → after = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать referencePoint=OUT")
        void shouldSupportOutReferencePoint() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_AFTER",
                    "referencePoint": "OUT",
                    "offsetMinutes": 0
                }
                """;

            // currentTime = 10:35, Out = 10:15 → after = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать referencePoint=ON")
        void shouldSupportOnReferencePoint() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_BEFORE",
                    "referencePoint": "ON",
                    "offsetMinutes": 0
                }
                """;

            // currentTime = 10:35, On = 12:45 → before = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен поддерживать referencePoint=IN")
        void shouldSupportInReferencePoint() {
            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_BEFORE",
                    "referencePoint": "IN",
                    "offsetMinutes": 0
                }
                """;

            // currentTime = 10:35, In = 13:05 → before = true
            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("должен вернуть false если опорное время отсутствует в контексте")
        void shouldReturnFalseWhenReferenceTimeMissing() {
            ExecutionContext emptyContext = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), context.currentFlightStage(),
                    context.currentTime(), new HashMap<>()
            );

            String criteria = """
                {
                    "type": "TIME_COMPARISON",
                    "operator": "IS_AFTER",
                    "referencePoint": "ETD",
                    "offsetMinutes": 0
                }
                """;

            assertThat(evaluator.evaluate(criteria, emptyContext, null)).isFalse();
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
    @DisplayName("getCustomFieldValue (P3-2)")
    class CustomFieldValueTests {

        @Test
        @DisplayName("должен вернуть значение поля рейса из additionalData по имени без префикса")
        void shouldReturnCustomFieldValue() {
            Map<String, Object> additionalData = new HashMap<>(context.additionalData());
            additionalData.put("customFields", Map.of("customField.GATE_NUMBER", "A12"));

            ExecutionContext contextWithCustomFields = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), context.currentFlightStage(),
                    context.currentTime(), additionalData
            );

            assertThat(evaluator.getCustomFieldValue(contextWithCustomFields, "GATE_NUMBER"))
                    .isEqualTo("A12");
        }

        @Test
        @DisplayName("должен вернуть null если поле отсутствует в карте customFields")
        void shouldReturnNullWhenFieldAbsent() {
            Map<String, Object> additionalData = new HashMap<>(context.additionalData());
            additionalData.put("customFields", Map.of("customField.GATE_NUMBER", "A12"));

            ExecutionContext contextWithCustomFields = new ExecutionContext(
                    context.aircraftId(), context.flightNumber(), context.currentFlightStage(),
                    context.currentTime(), additionalData
            );

            assertThat(evaluator.getCustomFieldValue(contextWithCustomFields, "UNKNOWN_FIELD")).isNull();
        }

        @Test
        @DisplayName("должен вернуть null если ключ customFields отсутствует в additionalData вовсе")
        void shouldReturnNullWhenCustomFieldsMapAbsent() {
            assertThat(evaluator.getCustomFieldValue(context, "GATE_NUMBER")).isNull();
        }
    }

    @Nested
    @DisplayName("COMPOUND критерий (AND/OR, вложенные группы)")
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

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
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

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
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

            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
        }

        @Test
        @DisplayName("OR: должен вернуть false если все дочерние критерии false")
        void shouldReturnFalseForOrWhenAllChildrenFalse() {
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
                            "targetStage": "IN"
                        }
                    ]
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
        }

        @Test
        @DisplayName("вложенная группа: (A OR B) AND C — должен корректно вычислять глубину > 1")
        void shouldEvaluateNestedGroups() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "AND",
                    "children": [
                        {
                            "type": "COMPOUND",
                            "operator": "OR",
                            "children": [
                                { "type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "INIT" },
                                { "type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "OFF" }
                            ]
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

            assertThat(evaluator.evaluate(criteria, context, null)).isTrue();
        }

        @Test
        @DisplayName("вложенная группа: false внутри глубокой OR-ветки не валит весь AND если другая ветка компенсирует")
        void shouldEvaluateNestedGroupsReturningFalse() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "AND",
                    "children": [
                        {
                            "type": "COMPOUND",
                            "operator": "OR",
                            "children": [
                                { "type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "INIT" },
                                { "type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "IN" }
                            ]
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

            // внутренний OR: false (ни INIT, ни IN) → весь AND = false
            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
        }

        @Test
        @DisplayName("должен вернуть false для COMPOUND без детей")
        void shouldReturnFalseForEmptyChildren() {
            String criteria = """
                {
                    "type": "COMPOUND",
                    "operator": "AND",
                    "children": []
                }
                """;

            assertThat(evaluator.evaluate(criteria, context, null)).isFalse();
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
