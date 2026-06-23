package ru.protectinfotrans.eca.integration.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageStatus;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P2-3: durable исходящий шлюз — реализация {@link MessageOutputPort} для {@code sendUplink}/
 * {@code sendGround}, заменяющая {@code LogMessageAdapter} для этих двух операций.
 *
 * <p><b>Контракт:</b> вызов СИНХРОННЫЙ и выполняется внутри транзакции перехода ECA-движка
 * ({@code ActionStepRule.execute} -> {@code advanceExecution}, см. ADR-0002 Decision п.2).
 * Этот метод НЕ отправляет сообщение во внешний канал — он только ПЕРСИСТИТ запись со статусом
 * {@link OutboundMessageStatus#PENDING} в ТОЙ ЖЕ транзакции БД, что и переход шага: атомарность
 * "решение движка поставить сообщение в очередь" + "факт постановки в durable-очередь"
 * гарантируется обычной транзакцией Spring/Hibernate (как и Outbox-запись {@code event_publication}
 * для остальных межмодульных уведомлений) — без участия внешнего брокера. Возвращаемое значение
 * {@code true} означает "успешно поставлено в durable-очередь", НЕ "успешно доставлено борту/
 * получателю" — фактическая доставка асинхронна, см. {@code OutboundMessageDeliveryScheduler}.
 *
 * <p>{@code @Primary}: {@code ActionStepRule} (execution) автовайрит ровно один бин
 * {@code MessageOutputPort} по типу — {@link LogMessageAdapter} остаётся в контексте как
 * симулятор реального канала для {@code OutboundMessageDeliveryScheduler}, но не должен сам стать
 * кандидатом инъекции вместо этого адаптера. (P3-3: raise/close condition больше не часть
 * {@code MessageOutputPort} — см. {@code conditions} модуль — поэтому этот класс больше не
 * делегирует в {@code LogMessageAdapter} для условий.)
 *
 * <p><b>Идемпотентность (фикс регрессии P1-4 x P2-3, см. ADR-0002 и javadoc
 * {@code ExecutionService#resumeRunningInstanceAfterRestart}):</b> 6/5-аргументные перегрузки
 * {@code sendUplink}/{@code sendGround} с {@code (executionInstanceId, stepOrderIndex)}
 * выполняют find-before-save по этому дедуп-ключу ПЕРЕД постановкой новой записи — повторный
 * прогон ACTION-шага (resume после рестарта, {@code ExecutionResumeRunner}) находит уже
 * существующую запись (созданную и закоммиченную ДО краша/рестарта — отдельная завершённая
 * транзакция) и идемпотентно пропускает создание дубля, возвращая {@code true} (как при
 * успешной постановке). Это покрывает РЕАЛЬНЫЙ сценарий регрессии: resume всегда
 * последовательный (один процесс читает состояние из БД после полного рестарта), а не
 * конкурентный сам с собой.
 *
 * <p><b>Почему НЕ перехватываем {@code DataIntegrityViolationException} здесь (отличие от
 * {@code EventProcessorService#receiveMessage}, P2-1):</b> этот метод вызывается СИНХРОННО
 * внутри УЖЕ ОТКРЫТОЙ транзакции перехода ECA-движка ({@code ExecutionService.advanceExecution}/
 * {@code startExecution}/{@code resumeRunningInstanceAfterRestart}) — постановка в очередь
 * ОБЯЗАНА коммититься/откатываться АТОМАРНО с переходом шага (см. абзац выше, "Контракт"), в
 * отличие от P2-1, где перехват вынесен в ОТДЕЛЬНЫЙ бин с собственной {@code @Transactional}-
 * границей именно для того, чтобы catch происходил СНАРУЖИ уже полностью откатившейся
 * транзакции. Перехват {@code DataIntegrityViolationException} здесь, ВНУТРИ той же транзакции,
 * был бы небезопасен: Hibernate Session уже невалидна после constraint violation на flush
 * (transaction marked rollback-only), и recovery-read в НЕЙ ЖЕ транзакции либо запрещён, либо
 * обречён откатиться вместе с ней — ровно та ошибка, которую P2-1 явно исправил, разделив слои.
 * Поэтому при реальном конфликте уникальности исключение проходит наверх и откатывает ВЕСЬ
 * переход целиком — это безопасно и корректно: {@code ExecutionInstance} защищён {@code @Version}
 * (оптимистическая блокировка) и сохраняется в этой же транзакции ДО вызова {@code sendUplink}/
 * {@code sendGround} ({@code executeTransition}), поэтому конкурентный повтор ОДНОГО И ТОГО ЖЕ
 * {@code (executionInstanceId, stepOrderIndex)} двумя независимыми транзакциями уже отражается
 * раньше, на {@code save(instance)}, конфликтом версии (см. {@code withOptimisticRetry}) — partial
 * UNIQUE индекс {@code (execution_instance_id, step_order_index)} (запрошен у db-dev, V27) здесь
 * чистая защита на уровне БД (defense in depth) на случай, если эта гарантия когда-либо
 * нарушится, а не ожидаемый рабочий путь требующий graceful recovery в этом методе.
 */
@Component("outboundMessageGatewayAdapter")
@Primary
@RequiredArgsConstructor
@Slf4j
public class OutboundMessageGatewayAdapter implements MessageOutputPort {

    private final OutboundMessageRepositoryPort repository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params) {
        return sendUplink(aircraftId, templateName, params, UplinkOrigin.COMPUTER_GENERATED);
    }

    @Override
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params, UplinkOrigin origin) {
        return sendUplink(aircraftId, templateName, params, origin, null, null);
    }

    @Override
    public boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params,
                               UplinkOrigin origin, Long executionInstanceId, Integer stepOrderIndex) {
        if (executionInstanceId != null && stepOrderIndex != null) {
            Optional<OutboundMessage> existing = repository.findByExecutionInstanceIdAndStepOrderIndex(
                    executionInstanceId, stepOrderIndex);
            if (existing.isPresent()) {
                log.info("Duplicate ACTION SEND_UPLINK detected (executionInstanceId={}, stepOrderIndex={}) — "
                                + "already queued as outbound message id={}, skipping re-enqueue "
                                + "(idempotent replay, e.g. resume after restart)",
                        executionInstanceId, stepOrderIndex, existing.get().getId());
                return true;
            }
        }

        OutboundMessage message = OutboundMessage.builder()
                .messageType(OutboundMessageType.UPLINK)
                .aircraftId(aircraftId)
                .templateName(templateName)
                .paramsJson(serializeParams(params))
                .uplinkOrigin(origin)
                .executionInstanceId(executionInstanceId)
                .stepOrderIndex(stepOrderIndex)
                .correlationId(CorrelationContext.getCorrelationId())
                .build();

        repository.save(message);
        log.info("[UPLINK] queued durably: aircraft={}, template={}, origin={}", aircraftId, templateName, origin);
        return true;
    }

    @Override
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params) {
        return sendGround(recipients, templateName, params, null, null);
    }

    @Override
    public boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params,
                               Long executionInstanceId, Integer stepOrderIndex) {
        if (executionInstanceId != null && stepOrderIndex != null) {
            Optional<OutboundMessage> existing = repository.findByExecutionInstanceIdAndStepOrderIndex(
                    executionInstanceId, stepOrderIndex);
            if (existing.isPresent()) {
                log.info("Duplicate ACTION SEND_GROUND detected (executionInstanceId={}, stepOrderIndex={}) — "
                                + "already queued as outbound message id={}, skipping re-enqueue "
                                + "(idempotent replay, e.g. resume after restart)",
                        executionInstanceId, stepOrderIndex, existing.get().getId());
                return true;
            }
        }

        OutboundMessage message = OutboundMessage.builder()
                .messageType(OutboundMessageType.GROUND)
                .recipients(recipients)
                .templateName(templateName)
                .paramsJson(serializeParams(params))
                .executionInstanceId(executionInstanceId)
                .stepOrderIndex(stepOrderIndex)
                .correlationId(CorrelationContext.getCorrelationId())
                .build();

        repository.save(message);
        log.info("[GROUND] queued durably: recipients={}, template={}", recipients, templateName);
        return true;
    }

    private String serializeParams(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params == null ? Map.of() : params);
        } catch (Exception e) {
            log.warn("Failed to serialize outbound message params, storing empty object", e);
            return "{}";
        }
    }
}
