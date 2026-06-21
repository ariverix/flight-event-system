package ru.protectinfotrans.eca.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P2-3/P2-6: durable-поллер фактической доставки исходящих сообщений (uplink/ground) во внешний
 * канал. {@code @Scheduled}-триггер сам не содержит бизнес-логики корректности — single-fire
 * (ровно одна попытка отправки на claim, без дублирования под конкуренцией нескольких реплик/
 * тиков) обеспечивается атомарным условным UPDATE {@code claimPending} в БД, по той же схеме,
 * что {@code WaitTimeoutScheduler}/{@code ExecutionJpaRepository#claimExpiredTimeout} (P1-5).
 *
 * <p><b>Переживание рестарта:</b> запись {@link OutboundMessage} со статусом {@code PENDING}
 * персистентна — если процесс упал сразу после {@code ActionStepRule}-перехода (запись уже
 * закоммичена), следующий тик ПОСЛЕ рестарта снова найдёт её через {@code findPendingCandidates}
 * и доставит — то же свойство durability, что у durable WAIT-таймаутов P1-5 и у resume RUNNING-
 * инстансов P1-4.
 *
 * <p><b>P2-6 — backoff:</b> сбой доставки больше не возвращает запись в {@code PENDING}
 * немедленно (как было в P2-3) — {@link OutboundBackoffPolicy} вычисляет
 * {@code nextAttemptAt = now + delay(attempts)} (экспоненциально растущая задержка), и
 * {@code findPendingCandidates} не подхватывает запись до этого момента — поллер не долбит
 * сбойный канал на каждом 5-секундном тике подряд.
 *
 * <p><b>P2-6 — circuit breaker:</b> {@link CircuitBreakerPolicy} (собственная лёгкая реализация,
 * CLOSED/OPEN/HALF_OPEN, durable-снимок в {@code ChannelCircuitBreaker} — см. её javadoc для
 * обоснования "почему не resilience4j") решает, можно ли пытаться доставить сообщение через канал
 * {@link OutboundMessageType} ПРЯМО СЕЙЧАС: серия сбоев (порог {@code DEFAULT_FAILURE_THRESHOLD})
 * открывает breaker — дальнейшие кандидаты этого канала блокируются fail-fast (статус остаётся
 * {@code PENDING}, попытка переносится на следующий тик, БЕЗ обращения к
 * {@code simulateChannelSend} и БЕЗ инкремента {@code attempts}/backoff — breaker и backoff
 * считают независимые вещи: backoff — "когда повторить ЭТО сообщение", breaker — "стоит ли вообще
 * сейчас слать что-либо в ЭТОТ канал"). После таймаута восстановления — ОДНА пробная HALF_OPEN
 * попытка (claim в БД не даёт двум кандидатам одного тика стать пробной попыткой одновременно);
 * успех закрывает breaker, сбой открывает его снова.
 *
 * <p><b>Канал доставки — заглушка (симуляция):</b> реального ACARS/AFTN-канала пока нет —
 * {@code deliver(...)} просто логирует и считается успешным (если только не включена тестовая
 * имитация сбоя через {@code params}, см. {@code simulateChannelSend} javadoc). Путь ДО этой
 * точки (статусная модель, claim, переживание рестарта, backoff, circuit breaker) — настоящий
 * durable-путь.
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

    private static final int MAX_ATTEMPTS = 5;

    private final OutboundMessageRepositoryPort repository;
    private final CircuitBreakerRepositoryPort circuitBreakerRepository;
    private final ObjectProvider<OutboundMessageDeliveryScheduler> self;
    private final OutboundBackoffPolicy backoffPolicy;
    private final CircuitBreakerPolicy circuitBreakerPolicy;
    private final ObjectMapper objectMapper;
    private final AtomicLong sentCounter;
    private final AtomicLong failedCounter;
    private final AtomicLong circuitOpenBlockedCounter;

    public OutboundMessageDeliveryScheduler(OutboundMessageRepositoryPort repository,
                                             CircuitBreakerRepositoryPort circuitBreakerRepository,
                                             ObjectProvider<OutboundMessageDeliveryScheduler> self,
                                             ObjectMapper objectMapper,
                                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.circuitBreakerRepository = circuitBreakerRepository;
        this.self = self;
        this.backoffPolicy = new OutboundBackoffPolicy();
        this.circuitBreakerPolicy = new CircuitBreakerPolicy();
        this.objectMapper = objectMapper;
        this.sentCounter = meterRegistry.gauge("eca.integration.outbound.sent", new AtomicLong(0));
        this.failedCounter = meterRegistry.gauge("eca.integration.outbound.failed", new AtomicLong(0));
        this.circuitOpenBlockedCounter = meterRegistry.gauge("eca.integration.outbound.circuit_open_blocked", new AtomicLong(0));
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

        OutboundMessageType channel = message.getMessageType();
        ChannelCircuitBreaker breakerState = circuitBreakerRepository.getOrCreate(channel);
        CircuitBreakerPolicy.Snapshot snapshot = toSnapshot(breakerState);
        CircuitBreakerPolicy.Decision decision = circuitBreakerPolicy.decideBeforeAttempt(snapshot, LocalDateTime.now());

        if (decision == CircuitBreakerPolicy.Decision.BLOCK) {
            // breaker OPEN, таймаут не истёк — fail-fast: канал НЕ трогаем (никакого вызова
            // simulateChannelSend), запись УЖЕ claim'ом переведена в SENDING — releaseClaim
            // возвращает её обратно в PENDING БЕЗ инкремента attempts/lastError (это решение
            // breaker'а про канал в целом, не сбой доставки конкретно ЭТОГО сообщения).
            // nextAttemptAt переносим на момент восстановления breaker'а (а не "прямо сейчас") —
            // не подхватываем тот же кандидат на КАЖДОМ 5-секундном тике, пока breaker точно
            // ещё открыт; breaker сам решит когда открыть HALF_OPEN-окно независимо от этого.
            LocalDateTime recoverAt = snapshot.openedAt() == null
                    ? LocalDateTime.now()
                    : snapshot.openedAt().plus(CircuitBreakerPolicy.DEFAULT_OPEN_TIMEOUT);
            repository.releaseClaim(id, recoverAt);
            circuitOpenBlockedCounter.incrementAndGet();
            log.warn("Outbound message {} delivery blocked — circuit breaker OPEN for channel {}", id, channel);
            return;
        }

        if (decision == CircuitBreakerPolicy.Decision.ALLOW_PROBE
                && !circuitBreakerRepository.claimHalfOpenProbe(channel)) {
            // другая попытка того же тика/другая реплика уже забрала единственную пробную
            // попытку — этот кандидат ждёт следующего тика, claim тоже возвращаем без последствий
            // для attempts/backoff этого сообщения.
            repository.releaseClaim(id, LocalDateTime.now());
            circuitOpenBlockedCounter.incrementAndGet();
            return;
        }

        try {
            simulateChannelSend(message);
            repository.markSent(id, LocalDateTime.now());
            circuitBreakerRepository.recordSuccess(channel);
            sentCounter.incrementAndGet();
        } catch (Exception e) {
            log.error("Outbound message {} delivery failed (channel={})", id, channel, e);

            CircuitBreakerPolicy.Snapshot afterFailure = circuitBreakerPolicy.onFailure(snapshot, LocalDateTime.now());
            boolean shouldOpen = afterFailure.state() == ru.protectinfotrans.eca.integration.domain.CircuitBreakerState.OPEN;
            circuitBreakerRepository.recordFailure(channel, shouldOpen, afterFailure.consecutiveFailures(),
                    afterFailure.openedAt());

            LocalDateTime nextAttemptTime = LocalDateTime.now().plus(backoffPolicy.delayFor(message.getAttempts()));
            repository.markFailed(id, e.getMessage(), MAX_ATTEMPTS, nextAttemptTime);
            failedCounter.incrementAndGet();
        }
    }

    private CircuitBreakerPolicy.Snapshot toSnapshot(ChannelCircuitBreaker breaker) {
        return new CircuitBreakerPolicy.Snapshot(breaker.getState(), breaker.getConsecutiveFailures(), breaker.getOpenedAt());
    }

    /**
     * Заглушка реального внешнего канала (ACARS/AFTN) — TODO: заменить на реальный
     * сетевой адаптер. Различение UPLINK/GROUND и origin/получателей сохранено в логе
     * для трассировки, как и в исходной {@code LogMessageAdapter}.
     *
     * <p><b>Тестовая имитация сбоя:</b> {@code params.__simulateFailure == true} (зашито в
     * {@code paramsJson} вызывающей стороной — IT-тесты P2-6) заставляет заглушку бросить
     * исключение вместо лога успеха — единственный способ детерминированно прогнать backoff/
     * circuit-breaker ветку {@link #deliverOne} на реальном Postgres ({@code claimPending}/
     * {@code markFailed}/{@code recordFailure} — настоящие БД-операции, заглушка только канал)
     * без необходимости поднимать реальный внешний ACARS/AFTN-канал, которого пока нет.
     *
     * <p><b>Почему {@link #shouldSimulateFailure} парсит JSON, а не делает substring-проверку:</b>
     * {@code paramsJson} проходит через Postgres {@code jsonb} (column definition
     * {@code OutboundMessage#paramsJson}) — при чтении/записи jsonb канонизирует текстовое
     * представление (например, {@code "{\"key\": true}"} С ПРОБЕЛОМ после {@code :}, в отличие
     * от компактного Jackson-вывода {@code "{\"key\":true}"} без пробела). Прямое сравнение
     * подстроки {@code "\"__simulateFailure\":true"} (без пробела) ломалось на реальном Postgres
     * в IT-окружении (через мок-репозиторий в unit-тестах этот путь не проходит — оттуда и
     * расхождение "зелёные unit, красные IT"). Разбор через {@link ObjectMapper} устойчив к
     * любому форматированию JSON-текста.
     */
    private void simulateChannelSend(OutboundMessage message) {
        if (shouldSimulateFailure(message)) {
            throw new IllegalStateException("Simulated channel failure for test (params.__simulateFailure=true)");
        }
        if (message.getMessageType() == OutboundMessageType.UPLINK) {
            log.info("[UPLINK] Sent to aircraft={}, template={}, origin={}, attempt={}",
                    message.getAircraftId(), message.getTemplateName(), message.getUplinkOrigin(),
                    message.getAttempts() + 1);
        } else {
            log.info("[GROUND] Sent to recipients={}, template={}, attempt={}",
                    message.getRecipients(), message.getTemplateName(), message.getAttempts() + 1);
        }
    }

    private boolean shouldSimulateFailure(OutboundMessage message) {
        String params = message.getParamsJson();
        if (params == null || params.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(params);
            JsonNode flag = node.get("__simulateFailure");
            return flag != null && flag.asBoolean(false);
        } catch (Exception e) {
            log.warn("Failed to parse outbound message params as JSON (id={}), assuming no simulated failure",
                    message.getId(), e);
            return false;
        }
    }
}
