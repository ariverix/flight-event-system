package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA репозиторий для экземпляров выполнения.
 */
public interface ExecutionJpaRepository extends JpaRepository<ExecutionInstance, Long> {

    Page<ExecutionInstance> findByStatus(ExecutionStatus status, Pageable pageable);

    Page<ExecutionInstance> findByAircraftId(String aircraftId, Pageable pageable);

    Page<ExecutionInstance> findBySequenceId(Long sequenceId, Pageable pageable);

    /**
     * Найти все активные (RUNNING или WAITING) экземпляры для конкретного ВС.
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE e.aircraftId = :aircraftId " +
           "AND e.status IN ('RUNNING', 'WAITING')")
    List<ExecutionInstance> findActiveByAircraftId(@Param("aircraftId") String aircraftId);

    // NOTE: эффективность зависит от индекса (status, wait_timeout_at) на таблице execution_instances
    @Query("SELECT e FROM ExecutionInstance e WHERE e.status = 'WAITING' " +
           "AND e.waitTimeoutAt IS NOT NULL AND e.waitTimeoutAt <= :now")
    List<ExecutionInstance> findWaitingWithExpiredTimeout(@Param("now") LocalDateTime now);

    /**
     * P1-5: атомарный claim одного просроченного WAIT-таймаута условным UPDATE.
     *
     * <p><b>Механизм:</b> UPDATE обнуляет {@code wait_timeout_at} (переводит его в
     * {@code NULL}) под условием {@code status = 'WAITING' AND wait_timeout_at = :expectedTimeout}.
     * {@code wait_timeout_at IS NULL} однозначно означает "для этого инстанса в данный момент
     * нет открытого таймаута, который нужно обрабатывать" — это та же семантика, которую
     * {@code ExecutionService#advanceExecution} уже использует при штатном resolved WAIT-шаге
     * (см. очистку {@code waitStartedAt}/{@code waitTimeoutAt} там же) — никакого НОВОГО
     * статуса/поля не вводится, переиспользуется существующий персистентный сигнал.
     *
     * <p><b>Почему это гарантирует single-fire под конкуренцией:</b> PostgreSQL берёт
     * эксклюзивную блокировку строки на время выполнения UPDATE. Если два потока/реплики
     * одновременно выполняют этот запрос с одним и тем же {@code id} и одним и тем же значением
     * {@code expectedTimeout} (тем, что было прочитано из {@code findWaitingWithExpiredTimeout}
     * вместе с id — см. вызывающий код {@code ExecutionService#checkWaitTimeouts}), ровно один
     * из них получает блокировку строки первым, видит {@code wait_timeout_at = :expectedTimeout}
     * ещё не изменённым и обновляет 1 запись (claim успешен). Второй поток блокируется на этой
     * же строке до коммита первого (обычная блокировка строки на UPDATE, без необходимости
     * SERIALIZABLE), а после разблокировки перечитывает строку УЖЕ с {@code wait_timeout_at = NULL} —
     * предикат {@code wait_timeout_at = :expectedTimeout} больше не совпадает (NULL никогда не
     * равен никакому значению в SQL), WHERE не находит строку, UPDATE второго потока обновляет
     * 0 записей. Вызывающая сторона ({@code ExecutionService#checkWaitTimeouts}) выполняет
     * бизнес-переход (advanceExecution с FAILURE) только если вернулось {@code 1} — поэтому
     * переход по таймауту срабатывает ровно один раз, даже если несколько поллеров (несколько
     * реплик backend, несколько перекрывшихся тиков {@code @Scheduled}) одновременно увидели
     * один и тот же просроченный инстанс в {@code findWaitingWithExpiredTimeout}.
     *
     * <p>Сравнение по конкретному {@code expectedTimeout} (а не просто {@code IS NOT NULL}) —
     * защита от редкого легитимного случая повторного визита того же WAIT-шага через GOTO
     * назад: claim не должен "проглотить" таймаут НОВОГО окна ожидания, открытого уже после
     * того, как кандидат был отобран в выборке, но до того, как этот UPDATE выполнился.
     *
     * <p>{@code clearAutomatically = true}: bulk UPDATE через JPQL обновляет строку напрямую
     * в БД, минуя Hibernate persistence context — без сброса кэша 1-го уровня объект
     * {@code ExecutionInstance}, ранее загруженный в ТЕКУЩЕЙ транзакции (например тем же
     * {@code findWaitingWithExpiredTimeout}), остался бы в сессии со старым (устаревшим)
     * {@code waitTimeoutAt}, и последующий {@code save()} этого же объекта (после успешного
     * claim, при выполнении бизнес-перехода) рисковал бы перезатереть {@code NULL} обратно
     * на старое значение при следующем flush. {@code clearAutomatically} принудительно очищает
     * persistence context сразу после UPDATE — все дальнейшие операции в той же транзакции
     * (включая {@code advanceExecution}/{@code save()} вызывающего кода) работают со свежим
     * состоянием, перечитанным из БД.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE ExecutionInstance e SET e.waitTimeoutAt = NULL " +
           "WHERE e.id = :id AND e.status = 'WAITING' AND e.waitTimeoutAt = :expectedTimeout")
    int claimExpiredTimeout(@Param("id") Long id, @Param("expectedTimeout") LocalDateTime expectedTimeout);

    /**
     * Все незавершённые экземпляры (RUNNING/WAITING) вне зависимости от ВС — для resume при старте (P1-4).
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE e.status IN ('RUNNING', 'WAITING')")
    List<ExecutionInstance> findAllActive();

    /**
     * Универсальный поиск с фильтрацией.
     */
    @Query("SELECT e FROM ExecutionInstance e WHERE " +
           "(:status IS NULL OR e.status = :status) AND " +
           "(:aircraftId IS NULL OR e.aircraftId = :aircraftId) AND " +
           "(:sequenceId IS NULL OR e.sequenceId = :sequenceId)")
    Page<ExecutionInstance> findByFilters(
            @Param("status") ExecutionStatus status,
            @Param("aircraftId") String aircraftId,
            @Param("sequenceId") Long sequenceId,
            Pageable pageable
    );

    /**
     * P1-7 (часть 2b, ADR-0002): дедуп-проверка перед {@code startExecution} —
     * опирается на индекс {@code idx_exec_dedup_trigger} (V23) по точно тем же четырём
     * колонкам и в том же порядке.
     */
    boolean existsBySequenceIdAndAircraftIdAndFlightNumberAndTriggeringMessageId(
            Long sequenceId, String aircraftId, String flightNumber, Long triggeringMessageId);
}
