package ru.protectinfotrans.eca.integration.application;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2-3: durable-поллер фактической доставки исходящих сообщений (uplink/ground) во внешний
 * канал. {@code @Scheduled}-триггер сам не содержит бизнес-логики корректности — single-fire
 * (ровно одна попытка отправки на claim, без дублирования под конкуренцией нескольких реплик/
 * тиков) обеспечивается атомарным условным UPDATE {@code claimPending} в БД, по той же схеме,
 * что {@code WaitTimeoutScheduler}/{@code ExecutionJpaRepository#claimExpiredTimeout} (P1-5).
 *
 * <p><b>Переживание рестарта:</b> запись {@link OutboundMessage} со статусом {@code PENDING}
 * персистентна — если процесс упал сразу после {@code ActionStepRule}-перехода (запись уже
 * закоммичена), следующий тик ПОСЛЕ рестарта снова найдёт её через {@code findPendingCandidates}
 * и доставит — то же свойство durability, что у WAIT-таймаутов P1-5 и у resume RUNNING-инстансов
 * P1-4.
 *
 * <p><b>Канал доставки — заглушка (симуляция):</b> реального ACARS/AFTN-канала пока нет —
 * {@code deliver(...)} просто логирует и считается успешным. Путь ДО этой точки (статусная
 * модель, claim, переживание рестарта) — настоящий durable-путь; полноценные ретраи с backoff
 * и circuit breaker на реальный внешний канал — отдельная задача P2-6 (здесь только базовый
 * счётчик попыток + статус FAILED при сбое claim/доставки, без backoff).
 *
 * <p><b>Self-инъекция через {@code ObjectProvider}:</b> {@link #pollPendingMessages} вызывает
 * {@link #deliverOne}, помеченный {@code @Transactional(REQUIRES_NEW)}, на ТОМ ЖЕ объекте —
 * прямой вызов {@code this.deliverOne(...)} был бы self-invocation, и Spring transactional proxy
 * НЕ перехватывает вызовы метода на {@code this} внутри того же объекта (аннотация была бы
 * безмолвно проигнорирована). Тот же приём, что в {@code ExecutionService} (P1-5) — см. javadoc
 * там для подробного объяснения.
 */
@Component
@Slf4j
public class OutboundMessageDeliveryScheduler {

    private static final int BATCH_SIZE = 50;

    /** Базовый повтор без backoff — полноценная стратегия повторов (backoff/circuit breaker) — P2-6. */
    private static final int MAX_ATTEMPTS = 5;

    private final OutboundMessageRepositoryPort repository;
    private final ObjectProvider<OutboundMessageDeliveryScheduler> self;
    private final AtomicLong sentCounter;
    private final AtomicLong failedCounter;

    public OutboundMessageDeliveryScheduler(OutboundMessageRepositoryPort repository,
                                             ObjectProvider<OutboundMessageDeliveryScheduler> self,
                                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.self = self;
        this.sentCounter = meterRegistry.gauge("eca.integration.outbound.sent", new AtomicLong(0));
        this.failedCounter = meterRegistry.gauge("eca.integration.outbound.failed", new AtomicLong(0));
    }

    /** Каждые 5 сек опрашиваем PENDING исходящие сообщения (durable claim в БД). */
    @Scheduled(fixedRate = 5000)
    public void pollPendingMessages() {
        try {
            List<OutboundMessage> candidates = repository.findPendingCandidates(LocalDateTime.now(), BATCH_SIZE);
            for (OutboundMessage candidate : candidates) {
                // через self (AOP-прокси), НЕ this — см. javadoc класса
                self.getObject().deliverOne(candidate.getId());
            }
        } catch (Exception e) {
            // один сбойный тик не должен останавливать @Scheduled навсегда
            log.error("pollPendingMessages tick failed — will retry on next scheduled run", e);
        }
    }

    /**
     * Claim + доставка ОДНОГО сообщения в собственной транзакции — сбой одного сообщения
     * (constraint violation/исключение) не должен испортить сессию для остальных кандидатов
     * в том же тике (тот же принцип, что у {@code ExecutionResumeRunner.run}, P1-4).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverOne(Long id) {
        if (!repository.claimPending(id)) {
            // claim не удался — другой поллер/реплика уже забрал это сообщение
            return;
        }

        OutboundMessage message = repository.findById(id).orElse(null);
        if (message == null) {
            return;
        }

        try {
            simulateChannelSend(message);
            repository.markSent(id, LocalDateTime.now());
            sentCounter.incrementAndGet();
        } catch (Exception e) {
            log.error("Outbound message {} delivery failed", id, e);
            repository.markFailed(id, e.getMessage(), MAX_ATTEMPTS);
            failedCounter.incrementAndGet();
        }
    }

    /**
     * Заглушка реального внешнего канала (ACARS/AFTN) — TODO: заменить на реальный
     * сетевой адаптер. Различение UPLINK/GROUND и origin/получателей сохранено в логе
     * для трассировки, как и в исходной {@code LogMessageAdapter}.
     */
    private void simulateChannelSend(OutboundMessage message) {
        if (message.getMessageType() == OutboundMessageType.UPLINK) {
            log.info("[UPLINK] Sent to aircraft={}, template={}, origin={}, attempt={}",
                    message.getAircraftId(), message.getTemplateName(), message.getUplinkOrigin(),
                    message.getAttempts() + 1);
        } else {
            log.info("[GROUND] Sent to recipients={}, template={}, attempt={}",
                    message.getRecipients(), message.getTemplateName(), message.getAttempts() + 1);
        }
    }
}
