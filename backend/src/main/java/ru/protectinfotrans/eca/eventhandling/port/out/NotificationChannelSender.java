package ru.protectinfotrans.eca.eventhandling.port.out;

import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;

/**
 * Выходной порт фактической отправки в канал (P3-4). Реализации — по одной на канал
 * ({@code EmailChannelSender}, {@code WebhookChannelSender}); {@code NotificationDispatchService}
 * выбирает подходящую по {@link #supports(NotificationChannelType)}.
 *
 * <p>Транспорт сейчас — лог-заглушка (как {@code LogMessageAdapter} для ACARS-вывода, осознанно:
 * реальный SMTP/HTTP-контур подключается без изменения этого контракта). Ценность P3-4 —
 * конфигурация (папки/обработчики), разрешение (наследование/override) и идемпотентный durable
 * реестр доставок, а не конкретный сетевой транспорт.
 */
public interface NotificationChannelSender {

    boolean supports(NotificationChannelType channel);

    /**
     * Отправить уведомление. Возврат {@code true} — доставлено (status SENT), {@code false} —
     * канал отклонил/недоступен (status FAILED, без исключения, чтобы один сбойный получатель не
     * срывал остальных).
     */
    boolean send(String target, String subject, String body);
}
