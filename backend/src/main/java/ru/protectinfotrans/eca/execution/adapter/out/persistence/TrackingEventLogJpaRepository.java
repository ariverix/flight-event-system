package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;

/**
 * Spring Data JPA репозиторий для Tracking Event Log (P1-8, V24).
 *
 * <p>Query-методы для просмотра журнала оператором (по sequence_id, aircraftId+flightNumber,
 * instanceId, диапазону created_at — под индексы из V24) добавляются вместе с частью 2
 * (observability-agent), когда появится конкретный use case чтения.
 */
public interface TrackingEventLogJpaRepository extends JpaRepository<TrackingEventLog, Long> {
}
