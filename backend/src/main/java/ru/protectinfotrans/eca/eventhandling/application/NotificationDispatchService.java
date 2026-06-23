package ru.protectinfotrans.eca.eventhandling.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import ru.protectinfotrans.eca.eventhandling.domain.DeliveryStatus;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationChannelSender;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationDeliveryRepositoryPort;
import ru.protectinfotrans.eca.execution.event.StepNotificationEvent;

import java.util.List;

/**
 * Доставка уведомлений по обработчикам событий (P3-4). Слушает {@link StepNotificationEvent}
 * (публикуется движком, когда у решения шага взведён Notify) через {@code @ApplicationModuleListener}
 * — at-least-once доставка из Event Publication Registry (Outbox, ADR-0002/P1-7).
 *
 * <p><b>Идемпотентность:</b> до фактической отправки проверяется durable дедуп-реестр
 * {@code notification_deliveries} по ключу {@code (executionId, stepIndex, result, handlerId)}
 * (частичный UNIQUE V34). Повторная доставка события (republish-on-restart/retry) → запись уже есть
 * → канал НЕ дёргается повторно. Гонка двух одновременных доставок ловится {@code DataIntegrity
 * ViolationException} на сохранении (defense-in-depth к exists-проверке).
 *
 * <p>Каждый обработчик доставляется независимо: сбой/конфликт по одному получателю не срывает
 * остальных в той же резолюции.
 */
@Service
@Slf4j
public class NotificationDispatchService {

    private final EventHandlerResolver resolver;
    private final NotificationDeliveryRepositoryPort deliveryRepository;
    private final List<NotificationChannelSender> channelSenders;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter duplicateSkippedCounter;

    public NotificationDispatchService(EventHandlerResolver resolver,
                                       NotificationDeliveryRepositoryPort deliveryRepository,
                                       List<NotificationChannelSender> channelSenders,
                                       MeterRegistry meterRegistry) {
        this.resolver = resolver;
        this.deliveryRepository = deliveryRepository;
        this.channelSenders = channelSenders;
        this.sentCounter = meterRegistry.counter("eca.notifications.sent");
        this.failedCounter = meterRegistry.counter("eca.notifications.failed");
        this.duplicateSkippedCounter = meterRegistry.counter("eca.notifications.duplicate_skipped");
    }

    @ApplicationModuleListener
    public void onStepNotification(StepNotificationEvent event) {
        List<EventHandler> handlers = resolver.resolve(event.sequenceId(), event.folderId());
        if (handlers.isEmpty()) {
            return;
        }
        String result = event.isSuccess() ? "SUCCESS" : "FAILURE";
        for (EventHandler handler : handlers) {
            if (!handler.getTriggerType().matches(event.isSuccess())) {
                continue;
            }
            deliver(event, result, handler);
        }
    }

    private void deliver(StepNotificationEvent event, String result, EventHandler handler) {
        if (deliveryRepository.existsByDedupKey(event.executionId(), event.stepIndex(), result, handler.getId())) {
            duplicateSkippedCounter.increment();
            log.debug("Уведомление уже доставлено (execution={}, step={}, result={}, handler={}) — пропуск",
                    event.executionId(), event.stepIndex(), result, handler.getId());
            return;
        }

        String subject = String.format("Шаг %d: %s (борт %s)", event.stepIndex(), result, event.aircraftId());
        String body = String.format(
                "Последовательность #%d, инстанс #%d: шаг %d завершился с результатом %s для борта %s.",
                event.sequenceId(), event.executionId(), event.stepIndex(), result, event.aircraftId());

        boolean ok = send(handler.getChannel(), handler.getTarget(), subject, body);

        try {
            deliveryRepository.save(NotificationDelivery.builder()
                    .executionId(event.executionId())
                    .stepIndex(event.stepIndex())
                    .result(result)
                    .handlerId(handler.getId())
                    .channel(handler.getChannel())
                    .target(handler.getTarget())
                    .status(ok ? DeliveryStatus.SENT : DeliveryStatus.FAILED)
                    .build());
            if (ok) {
                sentCounter.increment();
            } else {
                failedCounter.increment();
            }
        } catch (DataIntegrityViolationException race) {
            // Конкурентная доставка уже записала строку по тому же дедуп-ключу — наш канал мог
            // отработать второй раз (редкая гонка), но дубля строки нет; считаем как skip.
            duplicateSkippedCounter.increment();
            log.debug("Гонка доставки (execution={}, step={}, handler={}) — строка уже создана",
                    event.executionId(), event.stepIndex(), handler.getId());
        }
    }

    private boolean send(NotificationChannelType channel, String target, String subject, String body) {
        for (NotificationChannelSender sender : channelSenders) {
            if (sender.supports(channel)) {
                return sender.send(target, subject, body);
            }
        }
        log.warn("Нет отправителя для канала {} — уведомление помечено FAILED", channel);
        return false;
    }
}
