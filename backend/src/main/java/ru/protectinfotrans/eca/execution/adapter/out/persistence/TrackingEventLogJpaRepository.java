package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;

import java.util.Optional;

/**
 * Spring Data JPA репозиторий для Tracking Event Log (P1-8, V24).
 *
 * <p>Query-методы для просмотра журнала оператором (по sequence_id, aircraftId+flightNumber,
 * instanceId, диапазону created_at — под индексы из V24) добавляются вместе с частью 2
 * (observability-agent), когда появится конкретный use case чтения.
 *
 * <p>P7-4: {@link #findTopByInstanceIdOrderByIdDesc} используется WS-адаптером для
 * получения последней записи журнала после каждого события движка.
 */
public interface TrackingEventLogJpaRepository extends JpaRepository<TrackingEventLog, Long> {

    /**
     * Последняя запись журнала для данного инстанса (по убыванию id).
     * Используется в {@code WsEventBroadcaster} для broadcast event-log после коммита.
     */
    Optional<TrackingEventLog> findTopByInstanceIdOrderByIdDesc(Long instanceId);
}
