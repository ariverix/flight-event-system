package ru.protectinfotrans.eca.cluster;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * P2-3 (гигиена): продакшн-реализация {@link ApplicationReadiness}.
 *
 * <p>Флаг {@link #ready} взводится один раз на {@link ApplicationReadyEvent} — момент, когда контекст
 * полностью поднят, Flyway применил миграции, а пулы/бины инициализированы. До этого события
 * {@code isReady()} возвращает {@code false}, и {@code @Scheduled}-поллеры, гейтящие себя по нему
 * ({@code WaitTimeoutScheduler}, {@code OutboundMessageDeliveryScheduler}, {@code RetentionService}),
 * не выполняют бизнес-работу — не тикают до готовности схемы/приложения.
 *
 * <p>{@code volatile} — флаг взводится в потоке публикации {@code ApplicationReadyEvent}, а читается
 * из потоков планировщика ({@code @Scheduled}); volatile гарантирует видимость взвода без блокировок.
 */
@Component
@Slf4j
public class ApplicationReadinessGate implements ApplicationReadiness {

    private volatile boolean ready = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        this.ready = true;
        log.info("Application readiness gate open — @Scheduled поллеры разблокированы");
    }

    @Override
    public boolean isReady() {
        return ready;
    }
}
