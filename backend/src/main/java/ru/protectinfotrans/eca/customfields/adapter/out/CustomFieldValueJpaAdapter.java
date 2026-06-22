package ru.protectinfotrans.eca.customfields.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldValueRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code upsert} — read-modify-write (find existing row, mutate it, save) вместо native
 * {@code INSERT ... ON CONFLICT}: дешевле в реализации/тестировании, и горячий путь извлечения
 * (одно входящее сообщение → НЕБОЛЬШОЕ число совпавших правил, не batch) не оправдывает сложность
 * native upsert. Уникальный индекс {@code uq_custom_field_values_flight_field} (V32) — defense in
 * depth на случай конкурентной гонки (тот же принцип, что у {@code Template#name}/
 * {@code outbound_messages} partial unique index, P2-3): под READ COMMITTED два конкурентных
 * извлечения одного и того же поля для одного рейса теоретически могут оба не найти существующую
 * строку и попытаться вставить — проигравший получит {@code DataIntegrityViolationException} на
 * уровне БД; экстракция — best-effort путь (см. {@code CustomFieldExtractionUseCase} javadoc,
 * "не каждое сообщение обязано нести каждое поле"), специальный recovery-read как в P2-1 здесь
 * избыточен — конкурентная гонка по ОДНОМУ И ТОМУ ЖЕ полю ОДНОГО И ТОГО ЖЕ рейса в практике
 * ACARS-потока (последовательная обработка сообщений одного борта) крайне маловероятна, а при
 * возникновении — проигравшая попытка извлечения просто не применяется к этому конкретному
 * сообщению (значение уже записано победителем), что не теряет данные.
 */
@Repository
@RequiredArgsConstructor
public class CustomFieldValueJpaAdapter implements CustomFieldValueRepositoryPort {

    private final CustomFieldValueJpaRepository jpaRepository;

    @Override
    public Optional<CustomFieldValue> findByAircraftIdAndFlightNumberAndFieldName(
            String aircraftId, String flightNumber, String fieldName) {
        return jpaRepository.findByAircraftIdAndFlightNumberAndFieldName(aircraftId, flightNumber, fieldName);
    }

    @Override
    public CustomFieldValue upsert(CustomFieldValue value) {
        Optional<CustomFieldValue> existing = jpaRepository.findByAircraftIdAndFlightNumberAndFieldName(
                value.getAircraftId(), value.getFlightNumber(), value.getFieldName());

        CustomFieldValue toSave = existing.map(row -> {
            row.setValue(value.getValue());
            row.setSourceMessageId(value.getSourceMessageId());
            row.setExtractedAt(value.getExtractedAt() != null ? value.getExtractedAt() : LocalDateTime.now());
            // повторная запись значения снова открывает поле — см. javadoc порта
            row.setClosedAt(null);
            return row;
        }).orElse(value);

        return jpaRepository.save(toSave);
    }

    @Override
    public List<CustomFieldValue> findActiveByAircraftIdAndFlightNumber(String aircraftId, String flightNumber) {
        return jpaRepository.findActiveByAircraftIdAndFlightNumber(aircraftId, flightNumber);
    }

    @Override
    public int closeAllOpenForFlight(String aircraftId, String flightNumber, LocalDateTime closedAt) {
        return jpaRepository.closeAllOpenForFlight(aircraftId, flightNumber, closedAt);
    }
}
