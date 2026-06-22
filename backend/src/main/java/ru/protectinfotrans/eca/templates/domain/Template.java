package ru.protectinfotrans.eca.templates.domain;

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
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.time.LocalDateTime;

/**
 * Шаблон сообщения — паритет с SITA Sequencer: справочник конфигурируемых шаблонов для трёх
 * направлений (downlink/uplink/ground), используемых ACTION-шагами (send uplink/send ground)
 * и критерием message received (для сопоставления входящих сообщений).
 *
 * <p><b>{@link #messageType} (направление канала):</b>
 * <ul>
 *   <li>{@code DOWNLINK} — шаблон описывает ОЖИДАЕМОЕ сообщение от борта, используется только
 *       в критерии message received (сам Sequencer downlink не формирует и не отправляет);</li>
 *   <li>{@code UPLINK} — шаблон для ACTION send uplink, требует {@link #origin};</li>
 *   <li>{@code GROUND} — шаблон для ACTION send ground, требует {@link #origin}.</li>
 * </ul>
 *
 * <p><b>{@link #origin} (паритет с SITA — режим источника):</b>
 * <ul>
 *   <li>{@code COMPUTER_GENERATED} — система сама формирует и немедленно отправляет
 *       рендеринг шаблона при выполнении ACTION-шага;</li>
 *   <li>{@code EXTERNAL_USER} — "when triggered by the Sequencer": шаблон ЖДЁТ, пока внешний
 *       пользователь (диспетчер) не подготовит и не отпустит сообщение через Sequencer —
 *       ACTION-шаг лишь СТАВИТ запрос в очередь на ручное подтверждение, а не отправляет
 *       автоматически. Сама механика "ожидания отпуска оператором" (постановка в очередь
 *       на ручное подтверждение, UI диспетчера) — вне рамок P3-1, здесь фиксируется только
 *       семантика режима на уровне справочника шаблонов.</li>
 * </ul>
 * {@code null} только для {@code DOWNLINK} (входящее сообщение не имеет "происхождения формирования"
 * с точки зрения Sequencer — оно получено, а не сформировано).
 *
 * <p><b>{@link #body} и подстановка переменных:</b> текст шаблона с плейсхолдерами вида
 * {@code {{variableName}}} (см. {@code TemplateRenderer} — синтаксис и обоснование выбора).
 * Переменные подаются как {@code Map<String, Object>} на момент рендеринга — единая точка входа
 * для параметров ACTION-шага (P3-1) И для значений custom fields конкретного рейса (P3-2,
 * подаются туда же через тот же контракт {@code TemplateRenderPort#render}, без изменения
 * сигнатуры этим модулем).
 *
 * <p>Без FK на execution/sequence — тот же принцип "модуль templates самодостаточен", что у
 * {@code OutboundMessage}/{@code TrackingEventLog}: шаблон идентифицируется по {@link #name},
 * на которое ссылаются конфиги ACTION-шагов и критериев ПО ИМЕНИ (строкой), а не по FK.
 */
@Entity
@Table(name = "templates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Уникальное имя шаблона — то самое значение, на которое ссылается {@code templateName}
     *  в ACTION-конфиге (SEND_UPLINK/SEND_GROUND) и в критерии MESSAGE_RECEIVED. */
    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 20)
    private MessageType messageType;

    /** computer-generated | external-user. NULL для DOWNLINK — см. javadoc класса. */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 20)
    private UplinkOrigin origin;

    /** Свободный текст, группировка в UI (паритет SITA — "Position Request"/"Weather"/...). */
    @Column(name = "category", nullable = false, length = 100)
    private String category;

    /** Тело шаблона с плейсхолдерами {@code {{variableName}}}. */
    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    /** Выключенный шаблон не может быть выбран в новых ACTION-шагах (валидация на уровне
     *  сервиса), но существующие ссылки по имени не ломаются — мягкое выключение, не удаление. */
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
        if (category == null || category.isBlank()) {
            category = "GENERAL";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
