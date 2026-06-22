package ru.protectinfotrans.eca.customfields.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Текущее значение custom field в контексте КОНКРЕТНОГО РЕЙСА (по борту + номеру рейса) — паритет
 * с SITA Sequencer: "переиспользование данных, извлечённых из входящих сообщений, в исходящих".
 *
 * <p><b>Per-flight, не per-instance:</b> ключ — {@code (aircraftId, flightNumber, fieldName)}, а
 * НЕ {@code executionInstanceId}. Несколько {@code ExecutionInstance} (несколько запущенных
 * последовательностей) могут одновременно работать над одним и тем же рейсом и должны видеть ОДНО
 * И ТО ЖЕ значение custom field этого рейса — поэтому хранение отдельно от
 * {@code InstanceContext}/{@code ExecutionInstance#contextJson} (P1-3, тот контекст — per-instance:
 * from-this-point-only ссылки конкретного прогона конкретной последовательности).
 *
 * <p><b>"Текущее значение", не история:</b> таблица хранит ОДНУ строку на
 * {@code (aircraftId, flightNumber, fieldName)} (уникальный индекс, V32) — повторное извлечение
 * того же поля из более позднего сообщения ПЕРЕЗАПИСЫВАЕТ значение (последнее извлечённое
 * выигрывает), а не накапливает историю. Это соответствует семантике подстановки в шаблон/критерий
 * ("текущее значение поля рейса прямо сейчас"), а не журналу аудита.
 *
 * <p><b>{@link #fieldName} денормализовано (не FK на {@code CustomFieldRule}):</b> то же решение,
 * что у {@code Template#name}/{@code OutboundMessage} — переименование/удаление правила не должно
 * обрушивать уже извлечённые значения текущего рейса; подстановка читает ПО ИМЕНИ.
 *
 * <p><b>{@link #closedAt} — закрытие контекста при завершении рейса (паритет SITA):</b>
 * {@code null} = контекст рейса открыт, значение участвует в подстановке/критериях; не-null =
 * рейс завершён (стадия IN/SUMMARY) — значение ЗАМОРОЖЕНО (остаётся в БД для трассировки/аудита,
 * НЕ удаляется физически), но БОЛЬШЕ НЕ ВОЗВРАЩАЕТСЯ запросами чтения (см.
 * {@code CustomFieldQueryService#getActiveValues} — фильтрует {@code closedAt IS NULL}), то есть
 * не подставляется ни в шаблоны, ни в критерии. "Закрыть" здесь означает именно ЭТО — мягкое
 * исключение из активного контекста, не уничтожение данных (тот же принцип, что
 * {@code Template#active}/мягкое выключение, и что историческая ценность данных рейса для
 * последующего разбора инцидентов не должна теряться физическим DELETE).
 */
@Entity
@Table(name = "custom_field_values")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_name", nullable = false, length = 255)
    private String fieldName;

    @Column(name = "aircraft_id", nullable = false, length = 50)
    private String aircraftId;

    @Column(name = "flight_number", nullable = false, length = 50)
    private String flightNumber;

    @Column(name = "value", columnDefinition = "TEXT")
    private String value;

    /**
     * {@code IncomingMessage#id}, из которого было извлечено ТЕКУЩЕЕ значение — трассировка
     * ("откуда взялось это значение"), без FK (см. javadoc класса — "без FK" принцип проекта для
     * межмодульных по сути ссылок, customfields не зависит от схемы eventprocessor).
     */
    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(name = "extracted_at", nullable = false)
    private LocalDateTime extractedAt;

    /** Не-null = контекст рейса закрыт (рейс завершён) — см. javadoc класса. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    protected void onCreate() {
        if (extractedAt == null) {
            extractedAt = LocalDateTime.now();
        }
    }
}
