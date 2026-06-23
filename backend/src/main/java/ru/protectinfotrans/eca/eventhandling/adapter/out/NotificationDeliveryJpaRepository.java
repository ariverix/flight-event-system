package ru.protectinfotrans.eca.eventhandling.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;

interface NotificationDeliveryJpaRepository extends JpaRepository<NotificationDelivery, Long> {

    boolean existsByExecutionIdAndStepIndexAndResultAndHandlerId(
            Long executionId, Integer stepIndex, String result, Long handlerId);
}
