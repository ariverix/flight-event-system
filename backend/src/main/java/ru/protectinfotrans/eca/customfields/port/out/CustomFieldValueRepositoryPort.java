package ru.protectinfotrans.eca.customfields.port.out;

import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Выходной порт персистентности per-flight значений custom fields — реализуется JPA-адаптером. */
public interface CustomFieldValueRepositoryPort {

    /**
     * Текущее (возможно закрытое) значение поля рейса, если оно существует — для решения
     * "перезаписать существующую строку или вставить новую" в {@code upsert}.
     */
    Optional<CustomFieldValue> findByAircraftIdAndFlightNumberAndFieldName(
            String aircraftId, String flightNumber, String fieldName);

    /**
     * Создаёт новую запись либо ПЕРЕЗАПИСЫВАЕТ существующую строку
     * {@code (aircraftId, flightNumber, fieldName)} — "текущее значение, не история" (см.
     * {@code CustomFieldValue} javadoc). Повторное извлечение/перезапись СНОВА открывает значение
     * (сбрасывает {@code closedAt} в {@code null}) — намеренно: если поле того же имени пришло для
     * рейса заново ПОСЛЕ закрытия (теоретически возможно при гонке/повторной OOOI-метке до
     * фактического завершения), новое значение должно быть видимым, а не молча отброшенным как
     * "уже закрытое". Жизненный цикл закрытия управляется ИСКЛЮЧИТЕЛЬНО сменой стадии
     * ({@code closeFlightContext}), не имплицитно при записи значения.
     */
    CustomFieldValue upsert(CustomFieldValue value);

    /** Активные (не закрытые) значения рейса — горячий путь подстановки (см. {@code CustomFieldQueryUseCase}). */
    List<CustomFieldValue> findActiveByAircraftIdAndFlightNumber(String aircraftId, String flightNumber);

    /**
     * Закрывает (ставит {@code closedAt = closedAt} аргумента) все ещё открытые значения рейса —
     * паритет с SITA "контекст custom fields закрывается при завершении рейса".
     *
     * @return количество закрытых строк (для логирования/диагностики, 0 — рейс не имел открытых
     *         значений или уже был закрыт ранее — идемпотентно, не ошибка)
     */
    int closeAllOpenForFlight(String aircraftId, String flightNumber, LocalDateTime closedAt);
}
