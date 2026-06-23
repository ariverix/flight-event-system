package ru.protectinfotrans.eca.eventhandling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Обработчик событий (P3-4, паритет SITA Event Handling): «при таком-то исходе шага уведомить через
 * такой-то канал такого-то получателя». Привязан к ПАПКЕ ({@code scope=FOLDER}, наследуется) или к
 * ПОСЛЕДОВАТЕЛЬНОСТИ ({@code scope=SEQUENCE}, переопределяет наследование) — см.
 * {@code EventHandlerResolver} для семантики разрешения «ближайший уровень с конфигурацией выигрывает».
 *
 * <p>{@link #scopeId} — id папки (scope=FOLDER) или последовательности (scope=SEQUENCE).
 */
@Entity
@Table(name = "event_handlers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventHandler {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HandlerScope scope;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private NotificationTrigger triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannelType channel;

    @Column(nullable = false, length = 500)
    private String target;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
