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

    /**
     * Issue #1: атомарный инкремент {@code consecutiveFailures} прямо в SQL (было — absolute
     * {@code SET consecutiveFailures = :failures}, вычисленное вызывающей стороной над снимком,
     * прочитанным ДО этого UPDATE — под конкуренцией двух {@code deliverOne} того же канала это
     * терявший инкременты lost update: оба потока/реплики читают один и тот же устаревший счётчик,
     * оба независимо вычисляют "снимок+1" и оба пишут ОДНО И ТО ЖЕ число).
     *
     * <p>Теперь весь переход состояния читает {@code c.state}/{@code c.consecutiveFailures} ИЗ
     * ТЕКУЩЕЙ строки в момент выполнения UPDATE (Postgres берёт row-level lock на строку канала —
     * конкурентный второй UPDATE того же канала блокируется до коммита первого и видит уже
     * инкрементированное значение, а не устаревший снимок из Java) — сама возможность lost update
     * исключена конструктивно, без пессимистичных {@code SELECT ... FOR UPDATE}/{@code @Version}.
     *
     * <p>Семантика перехода — та же, что {@link ru.protectinfotrans.eca.integration.application.CircuitBreakerPolicy#onFailure}
     * (пришлось продублировать как чистый SQL ради атомарности — вычисление "открылся ли breaker"
     * должно происходить в ТОЙ ЖЕ статье UPDATE, а не в Java-коде над снимком, прочитанным раньше):
     * из {@code HALF_OPEN} сбой пробной попытки -> снова {@code OPEN} с НОВЫМ {@code openedAt},
     * счётчик не растёт дальше; из {@code CLOSED} — инкремент счётчика, при достижении
     * {@code failureThreshold} -> {@code OPEN} с {@code openedAt = :now}, иначе остаётся
     * {@code CLOSED} с прежним {@code openedAt}.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ChannelCircuitBreaker c SET "
            + "c.consecutiveFailures = CASE WHEN c.state = 'HALF_OPEN' THEN c.consecutiveFailures "
            + "ELSE c.consecutiveFailures + 1 END, "
            + "c.state = CASE WHEN c.state = 'HALF_OPEN' THEN 'OPEN' "
            + "WHEN c.consecutiveFailures + 1 >= :failureThreshold THEN 'OPEN' "
            + "ELSE 'CLOSED' END, "
            + "c.openedAt = CASE WHEN c.state = 'HALF_OPEN' THEN :now "
            + "WHEN c.consecutiveFailures + 1 >= :failureThreshold THEN :now "
            + "ELSE c.openedAt END "
            + "WHERE c.channel = :channel")
    void recordFailure(@Param("channel") OutboundMessageType channel,
                        @Param("failureThreshold") int failureThreshold, @Param("now") LocalDateTime now);

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
