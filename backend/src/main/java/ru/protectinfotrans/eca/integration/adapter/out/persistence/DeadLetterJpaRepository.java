package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;

import java.time.LocalDateTime;

public interface DeadLetterJpaRepository extends JpaRepository<DeadLetterMessage, Long> {

    Page<DeadLetterMessage> findByStatus(DeadLetterStatus status, Pageable pageable);

    long countByStatus(DeadLetterStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE DeadLetterMessage d SET d.status = 'REPROCESSED', d.reprocessedMessageId = :messageId, "
            + "d.lastAttemptAt = :attemptAt WHERE d.id = :id")
    void markReprocessed(@Param("id") Long id, @Param("messageId") Long reprocessedMessageId,
                          @Param("attemptAt") LocalDateTime attemptAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE DeadLetterMessage d SET d.attempts = d.attempts + 1, d.reason = :reason, "
            + "d.stackTrace = :stackTrace, d.lastAttemptAt = :attemptAt WHERE d.id = :id")
    void markReprocessFailed(@Param("id") Long id, @Param("reason") String reason,
                              @Param("stackTrace") String stackTrace, @Param("attemptAt") LocalDateTime attemptAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE DeadLetterMessage d SET d.status = 'DISCARDED' WHERE d.id = :id")
    void markDiscarded(@Param("id") Long id);
}
