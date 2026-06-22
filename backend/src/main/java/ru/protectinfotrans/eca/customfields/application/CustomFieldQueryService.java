package ru.protectinfotrans.eca.customfields.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;
import ru.protectinfotrans.eca.customfields.port.in.CustomFieldQueryUseCase;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldValueRepositoryPort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реализация {@link CustomFieldQueryUseCase} — главная точка чтения для execution/integration
 * (см. javadoc порта — единый формат с префиксом {@code "customField."} для обоих потребителей).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomFieldQueryService implements CustomFieldQueryUseCase {

    /** Тот же префикс, что зарезервирован точечной адресацией {@code TemplateRenderer} (P3-1). */
    static final String PREFIX = "customField.";

    private final CustomFieldValueRepositoryPort valueRepository;

    @Override
    public Map<String, String> getActiveValues(String aircraftId, String flightNumber) {
        if (aircraftId == null || flightNumber == null) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (CustomFieldValue value : valueRepository.findActiveByAircraftIdAndFlightNumber(aircraftId, flightNumber)) {
            result.put(PREFIX + value.getFieldName(), value.getValue());
        }
        return result;
    }
}
