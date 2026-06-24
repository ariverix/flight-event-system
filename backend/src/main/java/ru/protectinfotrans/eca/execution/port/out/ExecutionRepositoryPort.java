package ru.protectinfotrans.eca.execution.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Выходной порт для хранения экземпляров выполнения.
 *
 */
public interface ExecutionRepositoryPort {

    ExecutionInstance save(ExecutionInstance instance);

    Optional<ExecutionInstance> findById(Long id);

    Page<ExecutionInstance> findAll(Pageable pageable);

    /**
     * Поиск с фильтрацией по статусу, ВС и последовательности.
     */
    Page<ExecutionInstance> findByFilters(
            ExecutionStatus status,
            String aircraftId,
            Long sequenceId,
            Pageable pageable
    );

    /**
     * Найти все RUNNING/WAITING экземпляры для конкретного ВС.
     * Используется для checkStopCriteria и processWaitingInstances.
     */
    List<ExecutionInstance> findActiveByAircraftId(String aircraftId);

    /**
     * Найти все WAITING экземпляры с истёкшим таймаутом.
     * Используется в @Scheduled checkWaitTimeouts как источник КАНДИДАТОВ — сама выборка
     * не захватывает строки, поэтому конкурентные поллеры могут увидеть один и тот же
     * инстанс. Реальный single-fire даёт {@link #claimExpiredTimeout}.
     */
    List<ExecutionInstance> findWaitingWithExpiredTimeout(LocalDateTime now);

    /**
     * P1-5: атомарный claim одного просроченного WAIT-таймаута условным UPDATE
     * ({@code wait_timeout_at} переводится в {@code NULL} только если он ещё равен
     * {@code expectedTimeout} и статус ещё {@code WAITING}).
     *
     * @return {@code true}, если претензия (claim) удалась — ровно один вызывающий поток/реплика
     *         получает {@code true} для данного {@code (id, expectedTimeout)}, остальные
     *         конкурентные вызовы получают {@code false} и НЕ должны выполнять бизнес-переход.
     */
    boolean claimExpiredTimeout(Long id, LocalDateTime expectedTimeout);

    /**
     * Найти ВСЕ незавершённые экземпляры (RUNNING/WAITING) вне зависимости от ВС.
     * Используется при старте приложения (P1-4 resume) для восстановления незавершённых
     * инстансов после рестарта сервиса — {@code findActiveByAircraftId} тут не подходит,
     * так как на старте неизвестен конкретный борт.
     */
    List<ExecutionInstance> findAllActive();

    /** P5-1: число активных (RUNNING/WAITING) инстансов — для gauge активных последовательностей. */
    long countActive();

    /**
     * P1-7 (часть 2b, ADR-0002): дедуп-проверка перед {@code startExecution} — существует ли
     * уже {@code ExecutionInstance} с тем же {@code (sequenceId, aircraftId, flightNumber,
     * triggeringMessageId)}. Используется ТОЛЬКО когда {@code triggeringMessageId != null}
     * (старт, вызванный конкретным {@code NormalizedEvent.messageId}) — at-least-once
     * доставка Spring Modulith Event Publication Registry (republish on restart, retry) может
     * повторно доставить ОДНО И ТО ЖЕ событие, и без этой проверки повторная доставка создала
     * бы дублирующийся инстанс (см. ADR-0002, "Оценка текущего состояния").
     *
     * <p>Опирается на индекс {@code idx_exec_dedup_trigger} (V23, db-dev) —
     * {@code (sequence_id, aircraft_id, flight_number, triggering_message_id)} в этом
     * порядке: первые три колонки — точный идентификатор "какой именно запуск", последняя
     * (самая селективная, но и самая часто NULL у старых/безсобытийных записей) — сам
     * дедуп-ключ события.
     *
     * @param triggeringMessageId НЕ должен быть {@code null} — вызывающая сторона
     *                            ({@link ru.protectinfotrans.eca.execution.application.ExecutionService})
     *                            обязана сама решать, нужен ли дедуп для null-случая (старт не от
     *                            конкретного сообщения), и не вызывать этот метод в таком случае —
     *                            см. javadoc {@code ExecutionService#startExecution}.
     */
    boolean existsByDedupKey(Long sequenceId, String aircraftId, String flightNumber, Long triggeringMessageId);
}
