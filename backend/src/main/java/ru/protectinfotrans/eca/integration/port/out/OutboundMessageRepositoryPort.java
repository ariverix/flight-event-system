package ru.protectinfotrans.eca.integration.port.out;

import ru.protectinfotrans.eca.integration.domain.OutboundMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * P2-3: выходной порт хранения durable-очереди исходящих сообщений (uplink/ground).
 * Внутренний порт модуля {@code integration} — НЕ выставляется как named-interface наружу,
 * в отличие от {@code execution.port.out.MessageOutputPort}, который integration реализует.
 */
public interface OutboundMessageRepositoryPort {

    OutboundMessage save(OutboundMessage message);

    Optional<OutboundMessage> findById(Long id);

    /**
     * Найти ранее поставленную в очередь запись по дедуп-ключу
     * {@code (executionInstanceId, stepOrderIndex)} — фикс регрессии идемпотентности P1-4 x P2-3
     * (см. {@code OutboundMessage#executionInstanceId} javadoc). Используется ДО {@code save()}
     * новой записи: при совпадении повторный прогон ACTION-шага (resume после рестарта) не
     * создаёт вторую запись и не порождает повторную доставку во внешний канал.
     *
     * @param executionInstanceId идентификатор {@code ExecutionInstance}
     * @param stepOrderIndex индекс ACTION-шага
     * @return ранее поставленная запись для этого шага, если она есть
     */
    Optional<OutboundMessage> findByExecutionInstanceIdAndStepOrderIndex(Long executionInstanceId, Integer stepOrderIndex);

    /**
     * Кандидаты на доставку — сама выборка не захватывает строки, конкурентные поллеры
     * могут увидеть одну и ту же запись. Реальный single-fire даёт {@link #claimPending}.
     */
    List<OutboundMessage> findPendingCandidates(LocalDateTime now, int limit);

    /**
     * Атомарный claim одной PENDING-записи условным UPDATE {@code PENDING -> SENDING}
     * (по аналогии с {@code ExecutionRepositoryPort#claimExpiredTimeout}, P1-5).
     *
     * @return {@code true}, если claim удался — ровно один вызывающий поток/реплика получает
     *         {@code true} для данного {@code id}, остальные конкурентные вызовы получают
     *         {@code false} и не должны выполнять фактическую отправку.
     */
    boolean claimPending(Long id);

    void markSent(Long id, LocalDateTime sentAt);

    /**
     * Базовый повтор без backoff: переводит запись обратно в {@code PENDING}, если попыток
     * ещё не исчерпано ({@code attempts + 1 < maxAttempts}), иначе в терминальный
     * {@code FAILED}. Полноценные backoff/circuit breaker/DLQ-реинъекция — P2-6.
     */
    void markFailed(Long id, String error, int maxAttempts);
}
