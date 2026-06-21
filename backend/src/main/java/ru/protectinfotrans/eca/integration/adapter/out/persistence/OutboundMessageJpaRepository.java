package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboundMessageJpaRepository extends JpaRepository<OutboundMessage, Long> {

    /**
     * Кандидаты на доставку — старые сначала, ограничено {@code limit} (батч-размер поллера).
     * P2-6: {@code nextAttemptAt <= now} — backoff-фильтр (запись со сбоем не подхватывается до
     * истечения вычисленной задержки). Не захватывает строки — см. {@link #claimPending} для
     * реального single-fire.
     */
    @Query("SELECT m FROM OutboundMessage m WHERE m.status = 'PENDING' AND m.nextAttemptAt <= :now "
            + "ORDER BY m.createdAt ASC")
    List<OutboundMessage> findPendingCandidates(@Param("now") LocalDateTime now);

    /**
     * Дедуп-поиск по {@code (executionInstanceId, stepOrderIndex)} — фикс регрессии идемпотентности
     * P1-4 x P2-3 (см. {@code OutboundMessage#executionInstanceId} javadoc).
     */
    Optional<OutboundMessage> findByExecutionInstanceIdAndStepOrderIndex(Long executionInstanceId, Integer stepOrderIndex);

    /**
     * P2-3: атомарный claim одной PENDING-записи условным UPDATE {@code PENDING -> SENDING}
     * (по аналогии с {@code ExecutionJpaRepository#claimExpiredTimeout}, P1-5). Возвращает
     * количество обновлённых строк — {@code 1} означает, что claim удался, {@code 0} —
     * что запись уже забрал другой конкурентный поллер/реплика (или статус сменился).
     *
     * <p>{@code clearAutomatically = true} — bulk JPQL UPDATE минует Hibernate persistence
     * context, без сброса 1-го уровня кэша ранее загруженный в этой же транзакции объект
     * остался бы со старым (устаревшим) статусом — та же причина, что у claimExpiredTimeout.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboundMessage m SET m.status = 'SENDING' WHERE m.id = :id AND m.status = 'PENDING'")
    int claimPending(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboundMessage m SET m.status = 'SENT', m.sentAt = :sentAt WHERE m.id = :id")
    void markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

    /**
     * P2-6: повтор С экспоненциальным backoff — пока {@code attempts + 1 < maxAttempts}, запись
     * возвращается в {@code PENDING} с {@code nextAttemptAt = :nextAttemptTime} (не подхватывается
     * следующим тиком немедленно, только после истечения задержки); иначе переходит в
     * терминальный {@code FAILED} (оператор видит исчерпанные попытки в списке исходящих —
     * outbound DLQ-реинъекция не реализована: реальный внешний канал — заглушка, см.
     * {@code OutboundMessageDeliveryScheduler} javadoc, отличие от DLQ ВХОДЯЩИХ, где payload
     * нужен для повторного разбора).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboundMessage m SET m.attempts = m.attempts + 1, m.lastError = :error, "
            + "m.nextAttemptAt = :nextAttemptTime, "
            + "m.status = CASE WHEN m.attempts + 1 < :maxAttempts THEN 'PENDING' ELSE 'FAILED' END "
            + "WHERE m.id = :id")
    void markFailed(@Param("id") Long id, @Param("error") String error, @Param("maxAttempts") int maxAttempts,
                     @Param("nextAttemptTime") LocalDateTime nextAttemptTime);

    /**
     * P2-6: вернуть SENDING -> PENDING без инкремента attempts/lastError (circuit breaker
     * fail-fast блок — попытка доставки даже не начиналась, см. javadoc порта).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboundMessage m SET m.status = 'PENDING', m.nextAttemptAt = :nextAttemptTime WHERE m.id = :id")
    void releaseClaim(@Param("id") Long id, @Param("nextAttemptTime") LocalDateTime nextAttemptTime);
}
