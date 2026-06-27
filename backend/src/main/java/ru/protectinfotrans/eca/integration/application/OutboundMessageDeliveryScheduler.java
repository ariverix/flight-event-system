package ru.protectinfotrans.eca.integration.application;

import com.fasterxml.jackson.core.type.TypeReference;
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
import ru.protectinfotrans.eca.cluster.LeaderElection;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;
import ru.protectinfotrans.eca.templates.port.in.TemplateRenderUseCase;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final LeaderElection leaderElection;
    private final OutboundBackoffPolicy backoffPolicy;
    private final CircuitBreakerPolicy circuitBreakerPolicy;
    private final ObjectMapper objectMapper;
    private final TemplateRenderUseCase templateRenderUseCase;
    private final AtomicLong sentCounter;
    private final AtomicLong failedCounter;
    private final AtomicLong circuitOpenBlockedCounter;
    private final AtomicLong renderMissingVariableCounter;

    public OutboundMessageDeliveryScheduler(OutboundMessageRepositoryPort repository,
                                             CircuitBreakerRepositoryPort circuitBreakerRepository,
                                             ObjectProvider<OutboundMessageDeliveryScheduler> self,
                                             LeaderElection leaderElection,
                                             ObjectMapper objectMapper,
                                             TemplateRenderUseCase templateRenderUseCase,
                                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.circuitBreakerRepository = circuitBreakerRepository;
        this.self = self;
        this.leaderElection = leaderElection;
        this.backoffPolicy = new OutboundBackoffPolicy();
        this.circuitBreakerPolicy = new CircuitBreakerPolicy();
        this.objectMapper = objectMapper;
        this.templateRenderUseCase = templateRenderUseCase;
        this.sentCounter = meterRegistry.gauge("eca.integration.outbound.sent", new AtomicLong(0));
        this.failedCounter = meterRegistry.gauge("eca.integration.outbound.failed", new AtomicLong(0));
        this.circuitOpenBlockedCounter = meterRegistry.gauge("eca.integration.outbound.circuit_open_blocked", new AtomicLong(0));
        this.renderMissingVariableCounter = meterRegistry.gauge(
                "eca.integration.outbound.render_missing_variable", new AtomicLong(0));
    }

    /**
     * P6-1: автоматический {@code @Scheduled}-тик доставки — ТОЛЬКО на реплике-лидере (leader election
     * на PostgreSQL, {@link LeaderElection}), чтобы в кластере не опрашивали все реплики сразу.
     * Делегирует в {@link #pollPendingMessages()} (публичный, негейтуемый — его напрямую вызывают
     * интеграционные тесты). Корректность single-fire не зависит от лидерства: атомарный
     * {@code claimPending} в БД — defense-in-depth даже при кратком раздвоении лидерства.
     */
    @Scheduled(fixedRate = 5000)
    public void scheduledPoll() {
        if (!leaderElection.isLeader()) {
            return;
        }
        pollPendingMessages();
    }

    /** Опрашивает PENDING исходящие сообщения (durable claim в БД). Вызывается тиком {@link #scheduledPoll()}. */
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
            if (e instanceof MissingTemplateVariableException) {
                // см. javadoc #renderTemplate: шаблон НАЙДЕН, но ACTION-шаг не предоставил
                // значение плейсхолдера — ошибка КОНФИГУРАЦИИ, не инфраструктурный сбой канала.
                // НЕ markSent с именем шаблона как текстом (P3-1 production-дефект) — трактуем
                // как обычный сбой доставки (markFailed/backoff/circuit breaker ниже), но
                // дополнительно сигналим отдельным счётчиком/ERROR-логом с диагностикой для
                // оператора, настраивающего ACTION-шаг (aircraftId/templateName/недостающая
                // переменная), а не общим "delivery failed" логом ниже.
                renderMissingVariableCounter.incrementAndGet();
                log.error("Outbound message {} render failed — template '{}' found but missing variable "
                                + "for aircraft={}/recipients={}: {}",
                        id, message.getTemplateName(), message.getAircraftId(), message.getRecipients(),
                        e.getMessage());
            } else {
                log.error("Outbound message {} delivery failed (channel={})", id, channel, e);
            }

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

        // P3-1: рендеринг шаблона в готовый текст НЕПОСРЕДСТВЕННО перед фактической отправкой в
        // канал (а не на этапе постановки в очередь, ActionStepRule/OutboundMessageGatewayAdapter) —
        // безопасно для retries (backoff P2-6): рендеринг детерминирован (TemplateRenderer.render),
        // повторный вызов на той же (templateName, params) ВСЕГДА даёт тот же текст, поэтому
        // повторная попытка доставки после сбоя канала не меняет смысл сообщения. Не меняет
        // дедуп-ключ/схему OutboundMessage (P2-3) — params/templateName остаются единственным
        // персистентным состоянием, рендеринг — производная, вычисляемая on-the-fly.
        String renderedText = renderTemplate(message);

        if (message.getMessageType() == OutboundMessageType.UPLINK) {
            log.info("[UPLINK] Sent to aircraft={}, template={}, origin={}, attempt={}, text={}",
                    message.getAircraftId(), message.getTemplateName(), message.getUplinkOrigin(),
                    message.getAttempts() + 1, renderedText);
        } else {
            log.info("[GROUND] Sent to recipients={}, template={}, attempt={}, text={}",
                    message.getRecipients(), message.getTemplateName(), message.getAttempts() + 1, renderedText);
        }
    }

    /**
     * Рендерит тело шаблона через {@link TemplateRenderUseCase} — публичный порт модуля
     * {@code templates} (см. его javadoc: главная точка входа для execution/integration).
     * Параметры, ранее сериализованные в {@code OutboundMessage#paramsJson}
     * ({@code ActionStepRule#executeSendUplink/executeSendGround}), десериализуются обратно в
     * {@code Map<String, Object>} — единственная точка входа значений переменных сейчас (P3-2,
     * custom fields, дополнит эту карту на стороне вызывающего без изменения сигнатуры порта).
     *
     * <p><b>Почему {@link TemplateRenderUseCase#tryRender} (мягкий вариант), а не
     * {@code render} (бросающий {@code NoSuchElementException}):</b> до P3-1 {@code templateName}
     * был свободной строкой без реестра — существующие ACTION-шаги/сценарии (P1-x/P2-x) ссылаются
     * на имена шаблонов, для которых записи {@code Template} в БД никогда не создавались (и не
     * должны создаваться этой задачей — миграция демо-данных не входит в объём P3-1). Жёсткое
     * требование существования шаблона здесь сделало бы доставку ЭТИХ существующих сообщений
     * проваливающейся (FAILURE из incorrectly раскрытого NoSuchElementException), хотя до
     * появления движка шаблонов доставка была успешной — недопустимая регрессия для уже
     * работающего P2-3/P2-6 пути. Поэтому: если шаблон зарегистрирован в реестре — рендерим его
     * тело; если НЕТ — отправляем {@code templateName} как есть (обратная совместимость со
     * "старой" моделью, где шаблон — просто опознавательная строка, без тела/подстановки).
     *
     * <p><b>Доп. try/catch здесь, ХОТЯ {@code tryRender} уже сам не должен бросать (кроме
     * {@link MissingTemplateVariableException}, см. ниже):</b> {@code tryRender} выполняется
     * в своей собственной {@code REQUIRES_NEW}-транзакции — если внутри неё Hibernate помечает
     * транзакцию rollback-only (срабатывает на любой {@code DataAccessException}, например при
     * недоступности/несовместимости схемы {@code templates}), Spring бросает
     * {@code UnexpectedRollbackException} НА КОММИТЕ этой REQUIRES_NEW-транзакции — то есть уже
     * ПОСЛЕ внутреннего try/catch метода, перехватить его изнутри самого {@code tryRender}
     * невозможно. Эта транзакция изолирована от транзакции {@code deliverOne} (REQUIRES_NEW !=
     * поломка соединения вызывающего), поэтому здесь его безопасно проглотить и откатиться на то
     * же поведение — "шаблон не разрешился, шлём имя как есть" — без риска для
     * {@code markSent}/{@code markFailed} вызывающего.
     *
     * <p><b>{@link MissingTemplateVariableException} — единственное исключение, НЕ глушится
     * здесь, пробрасывается вызывающему ({@link #simulateChannelSend} → {@link #deliverOne}):</b>
     * это ОТЛИЧАЕТСЯ от "шаблон не найден"/"БД недоступна" (легитимный fallback на
     * {@code templateName}, обратная совместимость, см. выше) — шаблон НАЙДЕН, но ACTION-шаг
     * не предоставил значение одной из его переменных. Раньше это тоже сворачивалось в
     * {@code Optional.empty()} и тихо подменялось на {@code templateName}, из-за чего в канал
     * (борту/диспетчеру) уходило ИМЯ ШАБЛОНА как обычный текст, а {@code deliverOne} помечал
     * сообщение {@code markSent} — успешная доставка мусора без единого сигнала об ошибке. Это
     * production-дефект (см. javadoc {@link ru.protectinfotrans.eca.templates.application.TemplateRenderer}
     * — "явный сбой лучше тихой дыры"). Теперь это исключение долетает до {@code deliverOne}'s
     * catch и трактуется как обычный сбой канала доставки (markFailed/backoff/retry/circuit
     * breaker, P2-6), а НЕ как "шаблон не разрешился".
     */
    private String renderTemplate(OutboundMessage message) {
        Map<String, Object> variables = deserializeParams(message.getParamsJson());
        try {
            return templateRenderUseCase.tryRender(message.getTemplateName(), variables)
                    .orElse(message.getTemplateName());
        } catch (MissingTemplateVariableException e) {
            // намеренно НЕ ловим здесь — пробрасываем вызывающему как сбой доставки, см. javadoc
            throw e;
        } catch (RuntimeException e) {
            log.warn("Template render lookup failed for '{}' (isolated REQUIRES_NEW transaction), "
                    + "falling back to template name as-is: {}", message.getTemplateName(), e.toString());
            return message.getTemplateName();
        }
    }

    private Map<String, Object> deserializeParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize outbound message params, rendering with empty variables", e);
            return Map.of();
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
