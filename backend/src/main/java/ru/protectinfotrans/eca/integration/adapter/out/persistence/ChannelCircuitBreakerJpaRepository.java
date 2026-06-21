package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;

import java.time.LocalDateTime;

public interface ChannelCircuitBreakerJpaRepository extends JpaRepository<ChannelCircuitBreaker, OutboundMessageType> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChannelCircuitBreaker c SET c.state = 'CLOSED', c.consecutiveFailures = 0, "
            + "c.openedAt = NULL WHERE c.channel = :channel")
    void recordSuccess(@Param("channel") OutboundMessageType channel);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChannelCircuitBreaker c SET c.consecutiveFailures = :failures, "
            + "c.state = CASE WHEN :shouldOpen = true THEN 'OPEN' ELSE 'CLOSED' END, "
            + "c.openedAt = CASE WHEN :shouldOpen = true THEN :openedAt ELSE c.openedAt END "
            + "WHERE c.channel = :channel")
    void recordFailure(@Param("channel") OutboundMessageType channel, @Param("shouldOpen") boolean shouldOpen,
                        @Param("failures") int newConsecutiveFailures, @Param("openedAt") LocalDateTime openedAt);

    /**
     * P2-6: атомарный claim единственной HALF_OPEN пробной попытки — условный UPDATE
     * {@code OPEN -> HALF_OPEN}, по аналогии с {@code OutboundMessageJpaRepository#claimPending}
     * (P2-3/P1-5). Возвращает количество обновлённых строк — {@code 1} означает, что claim
     * удался, {@code 0} — что канал уже не в {@code OPEN} (другая реплика/тред уже забрал пробную
     * попытку, либо состояние изменилось).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChannelCircuitBreaker c SET c.state = 'HALF_OPEN' WHERE c.channel = :channel AND c.state = 'OPEN'")
    int claimHalfOpenProbe(@Param("channel") OutboundMessageType channel);
}
