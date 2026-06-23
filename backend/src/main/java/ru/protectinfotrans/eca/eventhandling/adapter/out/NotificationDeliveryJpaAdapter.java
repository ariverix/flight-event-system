package ru.protectinfotrans.eca.eventhandling.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationDelivery;
import ru.protectinfotrans.eca.eventhandling.port.out.NotificationDeliveryRepositoryPort;

@Component
@RequiredArgsConstructor
public class NotificationDeliveryJpaAdapter implements NotificationDeliveryRepositoryPort {

    private final NotificationDeliveryJpaRepository jpaRepository;

    @Override
    public boolean existsByDedupKey(Long executionId, Integer stepIndex, String result, Long handlerId) {
        return jpaRepository.existsByExecutionIdAndStepIndexAndResultAndHandlerId(
                executionId, stepIndex, result, handlerId);
    }

    @Override
    public NotificationDelivery save(NotificationDelivery delivery) {
        return jpaRepository.saveAndFlush(delivery);
    }
}
