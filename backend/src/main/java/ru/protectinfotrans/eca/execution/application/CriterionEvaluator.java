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

/** Вычисляет критерии ECA — все 6 типов плюс COMPOUND (AND/OR). */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriterionEvaluator {

    private final MessageRepositoryPort messageRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param waitStartedAt для fromThisPointOnly в WAIT-шагах, null в EVALUATE
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
                // история сообщений в БД — единственный источник правды для входящих событий
                case MESSAGE_RECEIVED -> evaluateMessageReceived(criteria, context, waitStartedAt);
                // стадия берётся из контекста текущего события, не из БД
                case FLIGHT_STAGE -> evaluateFlightStage(criteria, context);
                // POS-репорты ищем в скользящем временном окне в таблице messages
                case POSITION_REPORTED -> evaluatePositionReported(criteria, context);
                // ETD/ETA и т.п. должны быть заранее положены в context.additionalData
                case TIME_COMPARISON -> evaluateTimeComparison(criteria, context);
                // активные алерты живут в IntegrationService (in-memory), пробрасываются в контекст
                case CONDITION_ACTIVE -> evaluateConditionActive(criteria, context);
                // рекурсивный AND/OR — передаём waitStartedAt вглубь для fromThisPointOnly
                case COMPOUND -> evaluateCompound(criteria, context, waitStartedAt);
            };
        } catch (Exception e) {
            log.error("Failed to evaluate criterion: {}", criteriaJson, e);
            return false;
        }
    }

    private boolean evaluateMessageReceived(Map<String, Object> criteria, ExecutionContext context, LocalDateTime waitStartedAt) {
        MessageType messageType = MessageType.valueOf((String) criteria.get("messageType"));
        String templateName = (String) criteria.get("templateName");
        Boolean fromThisPointOnly = (Boolean) criteria.getOrDefault("fromThisPointOnly", false);

        // fromThisPointOnly=true — учитываем только сообщения после начала ожидания,
        // иначе старый POSITION_REPORT из прошлого рейса закроет WAIT-шаг сразу
        LocalDateTime afterTime = (fromThisPointOnly && waitStartedAt != null) ? waitStartedAt : null;

        return messageRepository.existsByAircraftAndTypeAndTemplate(
                context.aircraftId(),
                messageType,
                templateName,
                afterTime
        );
    }

    private boolean evaluateFlightStage(Map<String, Object> criteria, ExecutionContext context) {
        ComparisonOperator operator = ComparisonOperator.valueOf((String) criteria.get("operator"));
        FlightStage targetStage = FlightStage.valueOf((String) criteria.get("targetStage"));
        FlightStage currentStage = context.currentFlightStage();

        if (currentStage == null) {
            return false;
        }

        // FlightStage объявлен в хронологическом порядке (INIT→OUT→OFF→ON→IN),
        // поэтому ordinal() корректен для сравнений "раньше/позже"
        return switch (operator) {
            case EQUALS -> currentStage == targetStage;
            case NOT_EQUALS -> currentStage != targetStage;
            case GREATER_THAN -> currentStage.ordinal() > targetStage.ordinal();
            case LESS_THAN -> currentStage.ordinal() < targetStage.ordinal();
            case GREATER_OR_EQUAL -> currentStage.ordinal() >= targetStage.ordinal();
            case LESS_OR_EQUAL -> currentStage.ordinal() <= targetStage.ordinal();
        };
    }

    private boolean evaluatePositionReported(Map<String, Object> criteria, ExecutionContext context) {
        Integer minutesAgo = (Integer) criteria.get("minutesAgo");
        return messageRepository.existsPositionReportWithinMinutes(context.aircraftId(), minutesAgo);
    }

    private boolean evaluateTimeComparison(Map<String, Object> criteria, ExecutionContext context) {
        String operator = (String) criteria.get("operator");
        String referencePoint = (String) criteria.get("referencePoint");
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

    private boolean evaluateConditionActive(Map<String, Object> criteria, ExecutionContext context) {
        String conditionName = (String) criteria.get("conditionName");

        @SuppressWarnings("unchecked")
        Map<String, Boolean> activeConditions = (Map<String, Boolean>)
                context.additionalData().getOrDefault("activeConditions", Map.of());

        return activeConditions.getOrDefault(conditionName, false);
    }

    private boolean evaluateCompound(Map<String, Object> criteria, ExecutionContext context, LocalDateTime waitStartedAt) {
        String operator = (String) criteria.get("operator");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) criteria.get("children");

        if (children == null || children.isEmpty()) {
            return false;
        }

        // allMatch/anyMatch дают short-circuit: AND останавливается на первом false,
        // OR — на первом true, что важно при дорогих критериях типа MESSAGE_RECEIVED
        if ("AND".equals(operator)) {
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

    private LocalDateTime getReferenceTime(String referencePoint, ExecutionContext context) {
        return (LocalDateTime) context.additionalData().get(referencePoint.toLowerCase() + "Time");
    }
}
