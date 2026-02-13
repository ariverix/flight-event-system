package ru.protectinfotrans.eca.eventprocessor.domain;

import jakarta.persistence.*;
import lombok.*;
import ru.protectinfotrans.eca.MessageType;

import java.time.LocalDateTime;

/**
 * Входящее сообщение ACARS, полученное от внешней системы.
 *
 * См. диплом: раздел 1.1.2 (архитектура ACARS), раздел 1.3.4 (ключевые сущности)
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncomingMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private MessageType messageType;

    private String templateName;
    private String aircraftId;
    private String flightNumber;

    @Column(columnDefinition = "text")
    private String content;

    @Column(columnDefinition = "jsonb")
    private String metadataJson;

    private LocalDateTime receivedAt;

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
    }
}
