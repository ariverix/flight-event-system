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
import ru.protectinfotrans.eca.conditions.port.in.ConditionManagementUseCase;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldQueryUseCase;
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
import java.util.HashMap;
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
    private final CustomFieldQueryUseCase customFieldQueryUseCase;
    private final ConditionManagementUseCase conditionManagementUseCase;

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
                case SEND_UPLINK -> executeSendUplink(config, context, instance, step);
                case SEND_GROUND -> executeSendGround(config, context, instance, step);
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

    private boolean executeSendUplink(Map<String, Object> config, ExecutionContext context,
                                       ExecutionInstance instance, Step step) {
        String templateName = (String) config.get("templateName");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());
        params = mergeCustomFields(params, context);

        // uplinkOrigin — паритет с SITA Sequencer: computer-generated | from external user.
        // Отсутствие поля в конфиге (исторические/упрощённые сценарии) трактуем как
        // COMPUTER_GENERATED — это поведение "по умолчанию" для автоматических шагов сиквенсера.
        UplinkOrigin origin = config.containsKey("uplinkOrigin")
                ? UplinkOrigin.valueOf((String) config.get("uplinkOrigin"))
                : UplinkOrigin.COMPUTER_GENERATED;

        // instance.getId()/step.getOrderIndex() — дедуп-ключ идемпотентности durable-очереди
        // (фикс регрессии P1-4 x P2-3, см. javadoc MessageOutputPort.sendUplink(6 arg) и
        // ExecutionService.resumeRunningInstanceAfterRestart) — без него повторный прогон этого
        // ACTION-шага при resume после рестарта ставил бы дубль в outbound_messages.
        return messageOutputPort.sendUplink(context.aircraftId(), templateName, params, origin,
                instance.getId(), step.getOrderIndex());
    }

    private boolean executeSendGround(Map<String, Object> config, ExecutionContext context,
                                       ExecutionInstance instance, Step step) {
        String templateName = (String) config.get("templateName");
        @SuppressWarnings("unchecked")
        List<String> recipients = (List<String>) config.get("recipients");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) config.getOrDefault("params", Map.of());
        params = mergeCustomFields(params, context);

        return messageOutputPort.sendGround(recipients, templateName, params,
                instance.getId(), step.getOrderIndex());
    }

    /**
     * Объединяет параметры ACTION-конфига со значениями custom fields текущего рейса — паритет с
     * SITA Sequencer: "переиспользование данных, извлечённых из входящих сообщений, в исходящих"
     * (P3-2). Слияние происходит ЗДЕСЬ, на ACTION-шаге (а НЕ позже, в
     * {@code OutboundMessageDeliveryScheduler} непосредственно перед фактической доставкой) —
     * принципиальное решение для сохранения детерминированности повторных попыток доставки
     * (P2-6, backoff/circuit breaker): {@code params} становится единственным персистентным
     * состоянием рендеринга ({@code OutboundMessage#paramsJson}), замороженным В МОМЕНТ ACTION-
     * перехода, как и было задумано в P3-1 (см. javadoc {@code simulateChannelSend} —
     * "params/templateName остаются единственным персистентным состоянием"). Если бы custom field
     * подставлялся заново на КАЖДОЙ попытке доставки, а значение поля рейса успело бы измениться
     * между попытками (новое входящее сообщение с тем же именем поля) — повторная попытка
     * рендерила бы ДРУГОЙ текст для уже однажды поставленного в очередь сообщения, что нарушает
     * "один и тот же вход → один и тот же выход" (CLAUDE.md).
     *
     * <p>Явные {@code params} ACTION-конфига ИМЕЮТ ПРИОРИТЕТ над custom field того же имени
     * (insertion order {@code HashMap.putAll} — custom fields кладутся первыми, params вторыми и
     * перезатирают совпадающий ключ) — автор сценария, явно указавший параметр в конфиге шага,
     * сильнее автоматически извлечённого значения по той же позиции в карте переменных.
     */
    private Map<String, Object> mergeCustomFields(Map<String, Object> params, ExecutionContext context) {
        if (context.aircraftId() == null || context.flightNumber() == null) {
            return params;
        }
        Map<String, String> customFields = customFieldQueryUseCase.getActiveValues(
                context.aircraftId(), context.flightNumber());
        if (customFields.isEmpty()) {
            return params;
        }

        Map<String, Object> merged = new HashMap<>(customFields);
        merged.putAll(params);
        return merged;
    }

    /**
     * RAISE_CONDITION — паритет с SITA Sequencer (P3-3, движок условий/алертов в модуле
     * {@code conditions}). Уровень алерта ТЕПЕРЬ персистируется как реальный атрибут поднятого
     * условия (см. {@code RaisedCondition}), а не просто прокидывается в лог-заглушку — поэтому
     * (в отличие от прежнего поведения "warn и пропустить") нестандартное значение ВНЕ
     * канонического словаря No/Low/Medium/High/Critical теперь {@code IllegalArgumentException} →
     * перехватывается в {@link #execute}, ACTION-шаг возвращает {@code FAILURE}: автор сценария
     * решает через decision-граф (CONTINUE/GOTO/END/ABORT на false), что делать при опечатке в
     * уровне. Попытка поднять УЖЕ активное условие тем же именем —
     * {@code ConditionAlreadyRaisedException} (паритет "нельзя поднять дважды одним именем", см.
     * её javadoc) — тоже перехватывается там же, тоже {@code FAILURE}.
     */
    private boolean executeRaiseCondition(Map<String, Object> config, ExecutionContext context) {
        String conditionName = (String) config.get("conditionName");
        String alertLevelRaw = (String) config.getOrDefault("alertLevel", AlertLevel.NO.name());
        AlertLevel alertLevel = AlertLevel.valueOf(alertLevelRaw.toUpperCase());

        conditionManagementUseCase.raiseCondition(context.aircraftId(), context.flightNumber(), conditionName, alertLevel);
        return true;
    }

    private boolean executeCloseCondition(Map<String, Object> config, ExecutionContext context) {
        String conditionName = (String) config.get("conditionName");
        conditionManagementUseCase.closeCondition(context.aircraftId(), context.flightNumber(), conditionName);
        return true;
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
