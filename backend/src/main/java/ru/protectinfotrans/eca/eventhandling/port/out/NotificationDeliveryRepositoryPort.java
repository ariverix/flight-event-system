package ru.protectinfotrans.eca.eventhandling.port.out;

import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;

public interface NotificationDeliveryRepositoryPort {

    /** Уже доставлено по дедуп-ключу (execution, step, result, handler)? */
    boolean existsByDedupKey(Long executionId, Integer stepIndex, String result, Long handlerId);

    NotificationDelivery save(NotificationDelivery delivery);
}
