package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.sequence.domain.ComparisonOperator;
import ru.protectinfotrans.eca.sequence.domain.CriterionType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Оценщик критериев ECA-модели.
 * Поддерживает все 6 типов критериев + составные (COMPOUND).
 *
 * См. диплом: раздел 1.2.2 (Sequencer Criteria), раздел 1.3.3 (ECA)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriterionEvaluator {

    private final MessageRepositoryPort messageRepository;
    private final ObjectMapper objectMapper;

    /**
     * Оценить критерий на основе JSON-конфигурации и контекста выполнения.
     *
     * @param criteriaJson JSON-представление критерия
     * @param context контекст выполнения (aircraftId, flightStage, время)
     * @param waitStartedAt время начала ожидания (для fromThisPointOnly в WAIT-шагах, null для EVALUATE)
     * @return true если критерий выполнен
     */
    public boolean evaluate(String criteriaJson, ExecutionContext context, LocalDateTime waitStartedAt) {
        if (criteriaJson == null || criteriaJson.isBlank()) {
            log.warn("Empty criteria JSON, returning false");
            return false;
        }

        try {
            Map<String, Object> criteria = objectMapper.readValue(criteriaJson, new TypeReference<>() {});
            CriterionType type = CriterionType.valueOf((String) criteria.get("type"));

            return switch (type) {
                case MESSAGE_RECEIVED -> evaluateMessageReceived(criteria, context, waitStartedAt);
                case FLIGHT_STAGE -> evaluateFlightStage(criteria, context);
                case POSITION_REPORTED -> evaluatePositionReported(criteria, context);
                case TIME_COMPARISON -> evaluateTimeComparison(criteria, context);
                case CONDITION_ACTIVE -> evaluateConditionActive(criteria, context);
                case COMPOUND -> evaluateCompound(criteria, context, waitStartedAt);
            };
        } catch (Exception e) {
            log.error("Failed to evaluate criterion: {}", criteriaJson, e);
            return false;
        }
    }

    /**
     * MESSAGE_RECEIVED: Получено ли сообщение определённого типа и шаблона.
     * JSON: { "type": "MESSAGE_RECEIVED", "messageType": "DOWNLINK", "templateName": "...", "fromThisPointOnly": true/false }
     */
    private boolean evaluateMessageReceived(Map<String, Object> criteria, ExecutionContext context, LocalDateTime waitStartedAt) {
        MessageType messageType = MessageType.valueOf((String) criteria.get("messageType"));
        String templateName = (String) criteria.get("templateName");
        Boolean fromThisPointOnly = (Boolean) criteria.getOrDefault("fromThisPointOnly", false);

        LocalDateTime afterTime = (fromThisPointOnly && waitStartedAt != null) ? waitStartedAt : null;

        return messageRepository.existsByAircraftAndTypeAndTemplate(
                context.aircraftId(),
                messageType,
                templateName,
                afterTime
        );
    }

    /**
     * FLIGHT_STAGE: Проверка текущей стадии полёта.
     * JSON: { "type": "FLIGHT_STAGE", "operator": "EQUALS", "targetStage": "OFF" }
     */
    private boolean evaluateFlightStage(Map<String, Object> criteria, ExecutionContext context) {
        ComparisonOperator operator = ComparisonOperator.valueOf((String) criteria.get("operator"));
        FlightStage targetStage = FlightStage.valueOf((String) criteria.get("targetStage"));
        FlightStage currentStage = context.currentFlightStage();

        if (currentStage == null) {
            return false;
        }

        return switch (operator) {
            case EQUALS -> currentStage == targetStage;
            case NOT_EQUAL -> currentStage != targetStage;
            case GREATER -> currentStage.ordinal() > targetStage.ordinal();
            case LESS -> currentStage.ordinal() < targetStage.ordinal();
            case GREATER_EQUAL -> currentStage.ordinal() >= targetStage.ordinal();
            case LESS_EQUAL -> currentStage.ordinal() <= targetStage.ordinal();
        };
    }

    /**
     * POSITION_REPORTED: Получен ли позиционный отчёт за последние N минут.
     * JSON: { "type": "POSITION_REPORTED", "minutesAgo": 30 }
     */
    private boolean evaluatePositionReported(Map<String, Object> criteria, ExecutionContext context) {
        Integer minutesAgo = (Integer) criteria.get("minutesAgo");
        return messageRepository.existsPositionReportWithinMinutes(context.aircraftId(), minutesAgo);
    }

    /**
     * TIME_COMPARISON: Сравнение текущего времени с временной отметкой полёта.
     * JSON: { "type": "TIME_COMPARISON", "operator": "IS_AFTER", "referencePoint": "ETD", "offsetMinutes": 10 }
     */
    private boolean evaluateTimeComparison(Map<String, Object> criteria, ExecutionContext context) {
        String operator = (String) criteria.get("operator"); // IS_BEFORE, IS_EQUAL, IS_AFTER
        String referencePoint = (String) criteria.get("referencePoint"); // ETD, ETA, Off, On, In, Out
        Integer offsetMinutes = (Integer) criteria.getOrDefault("offsetMinutes", 0);

        LocalDateTime referenceTime = getReferenceTime(referencePoint, context);
        if (referenceTime == null) {
            log.warn("Reference time {} not available in context", referencePoint);
            return false;
        }

        LocalDateTime targetTime = referenceTime.plusMinutes(offsetMinutes);
        LocalDateTime now = context.currentTime();

        return switch (operator) {
            case "IS_BEFORE" -> now.isBefore(targetTime);
            case "IS_EQUAL" -> now.isEqual(targetTime);
            case "IS_AFTER" -> now.isAfter(targetTime);
            default -> false;
        };
    }

    /**
     * CONDITION_ACTIVE: Активно ли пользовательское условие (алерт).
     * JSON: { "type": "CONDITION_ACTIVE", "conditionName": "DELAYED" }
     */
    private boolean evaluateConditionActive(Map<String, Object> criteria, ExecutionContext context) {
        String conditionName = (String) criteria.get("conditionName");

        // Условия хранятся в additionalData контекста
        @SuppressWarnings("unchecked")
        Map<String, Boolean> activeConditions = (Map<String, Boolean>)
                context.additionalData().getOrDefault("activeConditions", Map.of());

        return activeConditions.getOrDefault(conditionName, false);
    }

    /**
     * COMPOUND: Составной критерий (AND/OR).
     * JSON: { "type": "COMPOUND", "operator": "AND", "children": [...] }
     */
    private boolean evaluateCompound(Map<String, Object> criteria, ExecutionContext context, LocalDateTime waitStartedAt) {
        String operator = (String) criteria.get("operator"); // AND, OR
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) criteria.get("children");

        if (children == null || children.isEmpty()) {
            return false;
        }

        if ("AND".equals(operator)) {
            // Все дочерние критерии должны быть true
            return children.stream().allMatch(child -> {
                try {
                    String childJson = objectMapper.writeValueAsString(child);
                    return evaluate(childJson, context, waitStartedAt);
                } catch (Exception e) {
                    log.error("Failed to evaluate child criterion", e);
                    return false;
                }
            });
        } else if ("OR".equals(operator)) {
            // Хотя бы один дочерний критерий должен быть true
            return children.stream().anyMatch(child -> {
                try {
                    String childJson = objectMapper.writeValueAsString(child);
                    return evaluate(childJson, context, waitStartedAt);
                } catch (Exception e) {
                    log.error("Failed to evaluate child criterion", e);
                    return false;
                }
            });
        }

        return false;
    }

    /**
     * Получить временную отметку из контекста.
     * Временные отметки (ETD, ETA, Off, On, In, Out) хранятся в additionalData.
     */
    private LocalDateTime getReferenceTime(String referencePoint, ExecutionContext context) {
        return (LocalDateTime) context.additionalData().get(referencePoint.toLowerCase() + "Time");
    }
}
