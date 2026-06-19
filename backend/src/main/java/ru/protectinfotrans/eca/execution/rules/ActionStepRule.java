package ru.protectinfotrans.eca.execution.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.annotation.Action;
import org.jeasy.rules.annotation.Condition;
import org.jeasy.rules.annotation.Fact;
import org.jeasy.rules.annotation.Rule;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionContext;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.sequence.domain.ActionType;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.sequence.domain.WaitTimeUnit;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Правило для ACTION-шагов — выполняет действие, возвращает SUCCESS/FAILURE. */
@Rule(name = "ActionStepRule", description = "Executes ACTION steps")
@Component
@org.springframework.context.annotation.Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class ActionStepRule {

    private final MessageOutputPort messageOutputPort;
    private final ObjectMapper objectMapper;

    private StepResult result;

    @Condition
    public boolean matches(@Fact("step") Step step) {
        return step.getStepType() == StepType.ACTION;
    }

    @Action
    public void execute(
            @Fact("step") Step step,
            @Fact("instance") ExecutionInstance instance,
            @Fact("context") ExecutionContext context
    ) {
        try {
            Map<String, Object> config = objectMapper.readValue(step.getConfigJson(), new TypeReference<>() {});
            ActionType actionType = ActionType.valueOf((String) config.get("actionType"));

            boolean success = switch (actionType) {
                case SEND_UPLINK -> executeSendUplink(config, context);
                case SEND_GROUND -> executeSendGround(config, context);
                case RAISE_CONDITION -> executeRaiseCondition(config, context);
                case CLOSE_CONDITION -> executeCloseCondition(config, context);
                case WAIT_TIME -> executeWaitTime(config, instance, context);
            };

            result = success ? StepResult.SUCCESS : StepResult.FAILURE;
            log.debug("ActionStepRule: {} executed with result {}", actionType, result);

        } catch (Exception e) {
            log.error("ActionStepRule execution failed", e);
            result = StepResult.FAILURE;
        }
    }

    public StepResult getResult() {
        return result;
    }

    public void reset() {
        this.result = null;
    }

    private boolean executeSendUplink(Map<String, Object> config, ExecutionContext context) {
        String templateName = (String) config.get("templateName");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());

        // uplinkOrigin — паритет с SITA Sequencer: computer-generated | from external user.
        // Отсутствие поля в конфиге (исторические/упрощённые сценарии) трактуем как
        // COMPUTER_GENERATED — это поведение "по умолчанию" для автоматических шагов сиквенсера.
        UplinkOrigin origin = config.containsKey("uplinkOrigin")
                ? UplinkOrigin.valueOf((String) config.get("uplinkOrigin"))
                : UplinkOrigin.COMPUTER_GENERATED;

        return messageOutputPort.sendUplink(context.aircraftId(), templateName, params, origin);
    }

    private boolean executeSendGround(Map<String, Object> config, ExecutionContext context) {
        String templateName = (String) config.get("templateName");
        @SuppressWarnings("unchecked")
        List<String> recipients = (List<String>) config.get("recipients");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());

        return messageOutputPort.sendGround(recipients, templateName, params);
    }

    private boolean executeRaiseCondition(Map<String, Object> config, ExecutionContext context) {
        String conditionName = (String) config.get("conditionName");
        String alertLevel = (String) config.getOrDefault("alertLevel", AlertLevel.NO.name());

        // Канонический словарь уровней — No/Low/Medium/High/Critical (паритет с SITA).
        // Не выбрасываем исключение на нестандартное значение (исторические сценарии
        // используют свободный текст типа "INFO"/"WARNING") — только предупреждаем в лог,
        // чтобы не ронять выполнение последовательности из-за расхождения словаря.
        try {
            AlertLevel.valueOf(alertLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("RAISE_CONDITION: alertLevel '{}' is outside canonical SITA vocabulary "
                    + "(No/Low/Medium/High/Critical) — passing through as-is", alertLevel);
        }

        return messageOutputPort.raiseCondition(context.aircraftId(), conditionName, alertLevel);
    }

    private boolean executeCloseCondition(Map<String, Object> config, ExecutionContext context) {
        String conditionName = (String) config.get("conditionName");
        return messageOutputPort.closeCondition(context.aircraftId(), conditionName);
    }

    // Thread.sleep нельзя — пишем waitTimeoutAt и уходим в WAITING.
    // Планировщик в ExecutionService сам поднимет по истечению.
    private boolean executeWaitTime(Map<String, Object> config, ExecutionInstance instance, ExecutionContext context) {
        long durationSeconds = resolveWaitDurationSeconds(config);

        LocalDateTime now = context.currentTime();
        instance.setWaitStartedAt(now);
        instance.setWaitTimeoutAt(now.plusSeconds(durationSeconds));
        instance.setStatus(ExecutionStatus.WAITING);

        log.info("WAIT_TIME: Set timeout for {} seconds until {}", durationSeconds, instance.getWaitTimeoutAt());

        // возвращаем true — переход в WAITING не является ошибкой действия
        return true;
    }

    /**
     * "wait for {x} {sec/min/hour}" — паритет с SITA Sequencer: автор сценария указывает
     * длительность в выбранной единице измерения, а не всегда в секундах.
     * Обратная совместимость: при отсутствии "unit" трактуем durationSeconds как секунды,
     * как и раньше (исторические сценарии/миграции V9, V14).
     */
    private long resolveWaitDurationSeconds(Map<String, Object> config) {
        Number durationValue = (Number) config.get("durationSeconds");
        if (durationValue == null) {
            log.warn("WAIT_TIME: durationSeconds missing in config, defaulting to 0");
            return 0L;
        }

        long duration = durationValue.longValue();
        if (!config.containsKey("unit")) {
            return duration;
        }

        WaitTimeUnit unit = WaitTimeUnit.valueOf((String) config.get("unit"));
        return switch (unit) {
            case SEC -> duration;
            case MIN -> duration * 60L;
            case HOUR -> duration * 3600L;
        };
    }
}
