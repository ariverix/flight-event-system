package ru.protectinfotrans.eca.eventhandling.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationChannelSender;

/**
 * Email-канал (P3-4). Транспорт — лог-заглушка (как {@code LogMessageAdapter} для ACARS-вывода):
 * реальный SMTP/ГОСТ-почтовый контур подключается без изменения порта
 * {@link NotificationChannelSender}. Базовая валидация адреса, чтобы заведомо мусорный target
 * помечался FAILED (а не «доставлен в никуда»).
 */
@Component
@Slf4j
public class EmailChannelSender implements NotificationChannelSender {

    @Override
    public boolean supports(NotificationChannelType channel) {
        return channel == NotificationChannelType.EMAIL;
    }

    @Override
    public boolean send(String target, String subject, String body) {
        if (target == null || !target.contains("@")) {
            log.warn("[EMAIL] невалидный адрес '{}' — не отправлено", target);
            return false;
        }
        log.info("[EMAIL] to={} subject='{}' body='{}'", target, subject, body);
        return true;
    }
}
