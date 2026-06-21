package ru.protectinfotrans.eca.integration.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * P2-6: выходной порт хранения DLQ-записей сбойных входящих сообщений. Внутренний порт модуля
 * {@code integration} — НЕ выставляется как named-interface наружу (тот же принцип, что
 * {@link OutboundMessageRepositoryPort}/{@link CallsignMatchingRepositoryPort}).
 */
public interface DeadLetterRepositoryPort {

    DeadLetterMessage save(DeadLetterMessage message);

    Optional<DeadLetterMessage> findById(Long id);

    Page<DeadLetterMessage> findAll(Pageable pageable);

    Page<DeadLetterMessage> findByStatus(DeadLetterStatus status, Pageable pageable);

    /**
     * Зафиксировать успешный reprocess (NEW/любой статус -> REPROCESSED) с привязкой к итоговому
     * {@code IncomingMessage}. Терминальный переход — реверс назад в NEW не предусмотрен (по
     * аналогии с {@code OutboundMessageStatus#SENT}, P2-3).
     */
    void markReprocessed(Long id, Long reprocessedMessageId, LocalDateTime attemptAt);

    /**
     * Зафиксировать повторный сбой reprocess — инкремент {@code attempts}, новая причина/стектрейс,
     * статус ОСТАЁТСЯ {@code NEW} (оператор может попробовать снова после исправления источника,
     * в отличие от durable outbound-ретраев P2-3/P2-6, здесь нет автоматического лимита попыток —
     * решение "сколько раз пробовать" принимает оператор, не планировщик).
     */
    void markReprocessFailed(Long id, String reason, String stackTrace, LocalDateTime attemptAt);

    /** Ручное решение оператора — сообщение не нужно, дальнейший reprocess не предполагается. */
    void markDiscarded(Long id);
}
