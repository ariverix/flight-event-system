package ru.protectinfotrans.eca.execution.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.domain.InstanceContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сериализация/десериализация {@link InstanceContext} в/из {@code ExecutionInstance#contextJson}
 * (колонка {@code context} JSONB, см. V4/V22). Использует тот же {@link ObjectMapper}, которым
 * уже сериализуются критерии/конфиги шагов в проекте — не вводим вторую библиотеку/конфигурацию.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstanceContextCodec {

    private final ObjectMapper objectMapper;

    /** Разобрать contextJson инстанса. Пусто/null/повреждённый JSON -> пустой контекст (round-trip не теряет стейт, но и не падает). */
    public InstanceContext decode(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return InstanceContext.empty();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(contextJson, new TypeReference<>() {});
            Object referencesNode = raw.get(InstanceContext.referencesKey());

            Map<String, LocalDateTime> references = new LinkedHashMap<>();
            if (referencesNode instanceof Map<?, ?> referencesMap) {
                for (Map.Entry<?, ?> entry : referencesMap.entrySet()) {
                    if (entry.getValue() == null) {
                        continue;
                    }
                    references.put(String.valueOf(entry.getKey()), LocalDateTime.parse(String.valueOf(entry.getValue())));
                }
            }
            return InstanceContext.of(references);
        } catch (Exception e) {
            log.warn("Failed to decode instance context JSON, starting from empty context: {}", contextJson, e);
            return InstanceContext.empty();
        }
    }

    /**
     * Сериализовать контекст обратно в JSON для записи в contextJson.
     * LocalDateTime сериализуем явно через toString() (ISO-8601), а не доверяем настройке
     * ObjectMapper-бина (JSR-310 модуль регистрируется автоконфигурацией Spring Boot в проде,
     * но это implementation detail вызывающей стороны — кодек не должен от него зависеть,
     * сам decode() уже строго ожидает ISO-формат через LocalDateTime.parse()).
     */
    public String encode(InstanceContext context) {
        try {
            Map<String, String> serializedReferences = new LinkedHashMap<>();
            context.rawReferences().forEach((stepIndex, referenceTime) ->
                    serializedReferences.put(stepIndex, referenceTime.toString()));

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put(InstanceContext.referencesKey(), serializedReferences);
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            log.error("Failed to encode instance context, falling back to empty object", e);
            return "{}";
        }
    }
}
