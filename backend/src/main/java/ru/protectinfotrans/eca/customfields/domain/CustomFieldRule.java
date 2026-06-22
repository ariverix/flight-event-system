package ru.protectinfotrans.eca.customfields.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.protectinfotrans.eca.MessageType;

import java.time.LocalDateTime;

/**
 * Правило извлечения custom field из входящего сообщения — паритет с SITA Sequencer: оператор
 * заводит правило "из сообщений типа X (опционально — конкретного шаблона) вытащить значение по
 * паттерну Y и положить под именем Z в контекст рейса".
 *
 * <p><b>{@link #name} — он же ключ подстановки:</b> то самое имя, что используется в плейсхолдере
 * шаблона {@code {{customField.NAME}}} (точечная адресация заготовлена в {@code TemplateRenderer},
 * P3-1) и в критерии {@code CONDITION_ACTIVE}-подобной форме чтения через
 * {@code ExecutionContext.additionalData}. Уникально — два правила с одинаковым именем были бы
 * неразличимы при подстановке (затирали бы значение друг друга в per-flight хранилище).
 *
 * <p><b>{@link #messageType} + {@link #templateName} (опционально):</b> правило применяется только
 * к входящим сообщениям этого типа (обычно DOWNLINK — паритет с SITA: custom field извлекается из
 * сообщения, ПРИШЕДШЕГО от борта/земли, а не из исходящего); если {@code templateName} задан —
 * дополнительно сужает к сообщениям, классифицированным под этим шаблоном (то же имя, на которое
 * ссылается критерий MESSAGE_RECEIVED, без FK — по имени, как и {@code Template#name}).
 *
 * <p>Без FK на {@code templates}/{@code messages} — тот же принцип "модуль самодостаточен по
 * имени, не по FK", что у {@code Template}/{@code OutboundMessage} (см. их javadoc).
 */
@Entity
@Table(name = "custom_field_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomFieldRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Уникальное имя поля — ключ {@code {{customField.NAME}}} и ключ per-flight хранения. */
    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** Тип входящего сообщения, к которому применяется правило (обычно DOWNLINK). */
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    /** Опциональное сужение до конкретного шаблона входящего (по имени, без FK). Null — любой шаблон. */
    @Column(name = "template_name", length = 255)
    private String templateName;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_source", nullable = false, length = 20)
    private ExtractionSource extractionSource;

    /**
     * {@code CONTENT} — regex с ровно одной capturing-группой; {@code METADATA} — имя ключа в
     * metadata. Семантика зависит от {@link #extractionSource} (см. {@link ExtractionSource}).
     */
    @Column(name = "pattern", nullable = false, columnDefinition = "TEXT")
    private String pattern;

    /** Выключенное правило не применяется при извлечении (мягкое выключение, как {@code Template#active}). */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
