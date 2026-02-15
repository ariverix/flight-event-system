package ru.protectinfotrans.eca.integration.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;

import java.util.List;
import java.util.Map;

/**
 * Log-заглушка для отправки исходящих сообщений.
 * В MVP просто логирует вызовы, в перспективе — реальная отправка через ACARS/HTTP.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (MessageOutputPort → Log-заглушка для MVP)
 */
@Component
@Slf4j
public class LogMessageAdapter implements MessageOutputPort {

    @Override
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        log.info("[UPLINK] Sending to aircraft={}, template={}, params={}", aircraftId, templateName, params);
        // В MVP всегда возвращаем true (успешно)
        return true;
    }

    @Override
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        log.info("[GROUND] Sending to recipients={}, template={}, params={}", recipients, templateName, params);
        // В MVP всегда возвращаем true (успешно)
        return true;
    }

    @Override
    public boolean raiseCondition(String aircraftId, String conditionName, String alertLevel) {
        log.warn("[CONDITION] Raising condition '{}' for aircraft={}, level={}", conditionName, aircraftId, alertLevel);
        // В MVP всегда возвращаем true (успешно)
        return true;
    }

    @Override
    public boolean closeCondition(String aircraftId, String conditionName) {
        log.info("[CONDITION] Closing condition '{}' for aircraft={}", conditionName, aircraftId);
        // В MVP всегда возвращаем true (успешно)
        return true;
    }
}
