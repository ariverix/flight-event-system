package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.port.out.TrackingEventLogPort;

/**
 * JPA-адаптер для Tracking Event Log (P1-8, V24). Логику ЗАПИСИ (что и когда сохранять —
 * старт/стоп последовательности, завершение шага, проверка {@code logging_enabled})
 * реализует observability-agent (часть 2) поверх {@link TrackingEventLogPort}.
 */
@Repository
@RequiredArgsConstructor
public class TrackingEventLogJpaAdapter implements TrackingEventLogPort {

    private final TrackingEventLogJpaRepository jpaRepository;

    @Override
    public TrackingEventLog save(TrackingEventLog event) {
        return jpaRepository.save(event);
    }
}
