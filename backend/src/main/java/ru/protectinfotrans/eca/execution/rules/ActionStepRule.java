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
import ru.protectinfotrans.eca.sequence.domain.Step;
import ru.protectinfotrans.eca.sequence.domain.StepType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Easy Rules правило для шагов типа ACTION.
 * Выполняет действие и возвращает SUCCESS или FAILURE.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Actions), раздел 1.3.3 (ECA модель), таблица 1.3
 */
@Rule(name = "ActionStepRule", description = "Executes ACTION steps")
@Component
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

        return messageOutputPort.sendUplink(context.aircraftId(), templateName, params);
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
        String alertLevel = (String) config.getOrDefault("alertLevel", "INFO");

        return messageOutputPort.raiseCondition(context.aircraftId(), conditionName, alertLevel);
    }

    private boolean executeCloseCondition(Map<String, Object> config, ExecutionContext context) {
        String conditionName = (String) config.get("conditionName");
        return messageOutputPort.closeCondition(context.aircraftId(), conditionName);
    }

    /**
     * WAIT_TIME: Установить таймаут ожидания.
     * НЕ используем Thread.sleep! Записываем waitTimeoutAt и меняем статус на WAITING.
     * @Scheduled в ExecutionService обнаружит истечение таймаута.
     */
    private boolean executeWaitTime(Map<String, Object> config, ExecutionInstance instance, ExecutionContext context) {
        Integer durationSeconds = (Integer) config.get("durationSeconds");

        LocalDateTime now = context.currentTime();
        instance.setWaitStartedAt(now);
        instance.setWaitTimeoutAt(now.plusSeconds(durationSeconds));
        instance.setStatus(ExecutionStatus.WAITING);

        log.info("WAIT_TIME: Set timeout for {} seconds until {}", durationSeconds, instance.getWaitTimeoutAt());

        // Возвращаем true — переход в WAITING не является ошибкой
        // Когда таймаут истечёт, @Scheduled checkWaitTimeouts() установит result = FAILURE и продолжит выполнение
        return true;
    }
}
