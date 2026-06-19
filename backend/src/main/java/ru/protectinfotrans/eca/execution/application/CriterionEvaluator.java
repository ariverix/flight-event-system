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
import ru.protectinfotrans.eca.sequence.domain.PositionSource;
import ru.protectinfotrans.eca.sequence.domain.TimeOperator;
import ru.protectinfotrans.eca.sequence.domain.TimeReferencePoint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Вычисляет критерии ECA — паритет с SITA Sequencer: message received, flight stage,
 * position, time, плюс расширение CONDITION_ACTIVE и комбинатор COMPOUND (AND/OR).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CriterionEvaluator {

    private final MessageRepositoryPort messageRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param waitStartedAt для fromThisPointOnly в WAIT-шагах (message/position критерии), null в EVALUATE
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
                // POS-репорты ищем в скользящем временном окне в таблице messages,
                // оценочные позиции игнорируются, fromThisPointOnly поддержан как и для MESSAGE_RECEIVED
                case POSITION_REPORTED -> evaluatePosition(criteria, context, waitStartedAt);
                // ETD/ETA/Init/Out/Off/On/In должны быть заранее положены в context.additionalData
                case TIME_COMPARISON -> evaluateTime(criteria, context);
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

        // FlightStage объявлен в хронологическом порядке (Init/Out/Off/On/In/Summary),
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

    /**
     * POSITION-критерий — паритет с SITA Sequencer:
     * reported|not reported + in the last {x} min, источник ACARS/radar/ADS-B (опционально),
     * оценочные (estimated) позиции игнорируются, опционально fromThisPointOnly.
     */
    private boolean evaluatePosition(Map<String, Object> criteria, ExecutionContext context, LocalDateTime waitStartedAt) {
        // reported=true (по умолчанию) — "position reported"; reported=false — "position not reported"
        boolean reported = (Boolean) criteria.getOrDefault("reported", true);
        Integer minutesAgo = (Integer) criteria.get("minutesAgo");
        if (minutesAgo == null) {
            log.warn("POSITION criterion missing minutesAgo");
            return false;
        }

        PositionSource source = criteria.get("source") != null
                ? PositionSource.valueOf((String) criteria.get("source"))
                : null;

        Boolean fromThisPointOnly = (Boolean) criteria.getOrDefault("fromThisPointOnly", false);
        LocalDateTime afterTime = (fromThisPointOnly && waitStartedAt != null) ? waitStartedAt : null;

        LocalDateTime now = context.currentTime() != null ? context.currentTime() : LocalDateTime.now();
        LocalDateTime sinceTime = now.minusMinutes(minutesAgo);

        boolean actuallyReported = messageRepository.existsActualPositionReportSince(
                context.aircraftId(),
                sinceTime,
                source,
                afterTime
        );

        // "not reported" — это инверсия: окно {x} мин не содержит ни одного фактического отчёта.
        // "Off"-таймстамп как точка отсчёта для not-reported учитывается через afterTime/waitStartedAt
        // самого WAIT-шага (вызывающая сторона передаёт сюда момент Off как waitStartedAt при необходимости).
        return reported ? actuallyReported : !actuallyReported;
    }

    /**
     * TIME-критерий — паритет с SITA Sequencer: is before|is equal to|is after
     * опорная точка ETD/ETA/Init/Out/Off/On/In ± {x} мин.
     */
    private boolean evaluateTime(Map<String, Object> criteria, ExecutionContext context) {
        TimeOperator operator = TimeOperator.valueOf((String) criteria.get("operator"));
        TimeReferencePoint referencePoint = TimeReferencePoint.valueOf(
                ((String) criteria.get("referencePoint")).toUpperCase());
        Integer offsetMinutes = (Integer) criteria.getOrDefault("offsetMinutes", 0);

        LocalDateTime referenceTime = getReferenceTime(referencePoint, context);
        if (referenceTime == null) {
            log.warn("Reference time {} not available in context", referencePoint);
            return false;
        }

        LocalDateTime targetTime = referenceTime.plusMinutes(offsetMinutes);
        LocalDateTime now = context.currentTime();

        return switch (operator) {
            case IS_BEFORE -> now.isBefore(targetTime);
            case IS_EQUAL -> now.isEqual(targetTime);
            case IS_AFTER -> now.isAfter(targetTime);
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
        // OR — на первом true, что важно при дорогих критериях типа MESSAGE_RECEIVED/POSITION
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

    private LocalDateTime getReferenceTime(TimeReferencePoint referencePoint, ExecutionContext context) {
        return (LocalDateTime) context.additionalData().get(referencePoint.name().toLowerCase() + "Time");
    }
}
