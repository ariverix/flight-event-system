package ru.protectinfotrans.eca.eventhandling.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationChannelSender;

/**
 * Webhook-канал (P3-4): HTTP POST на сконфигурированный URL. Транспорт — лог-заглушка (как для
 * ACARS-вывода): реальный HTTP-клиент (RestClient) подключается без изменения порта
 * {@link NotificationChannelSender}. Базовая валидация URL, чтобы мусорный target помечался FAILED.
 */
@Component
@Slf4j
public class WebhookChannelSender implements NotificationChannelSender {

    @Override
    public boolean supports(NotificationChannelType channel) {
        return channel == NotificationChannelType.WEBHOOK;
    }

    @Override
    public boolean send(String target, String subject, String body) {
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            log.warn("[WEBHOOK] невалидный URL '{}' — не отправлено", target);
            return false;
        }
        log.info("[WEBHOOK] POST {} subject='{}' body='{}'", target, subject, body);
        return true;
    }
}
