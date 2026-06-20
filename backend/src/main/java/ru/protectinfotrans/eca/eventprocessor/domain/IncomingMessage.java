package ru.protectinfotrans.eca.eventprocessor.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;

/**
 * Входящее сообщение ACARS, полученное от внешней системы.
 *
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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadataJson;

    private LocalDateTime receivedAt;

    /**
     * Источник позиционного отчёта (ACARS/RADAR/ADS_B). Null для немпозиционных сообщений.
     * Используется POSITION-критерием — паритет с SITA Sequencer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "position_source")
    private PositionSource positionSource;

    /**
     * true — позиция оценочная (estimated), а не фактическая.
     * POSITION-критерий обязан игнорировать оценочные позиции — паритет с SITA Sequencer.
     */
    @Builder.Default
    @Column(name = "is_estimated_position", nullable = false)
    private boolean estimatedPosition = false;

    /**
     * Идентификатор сообщения от внешней ACARS-системы (ARINC message reference,
     * AFTN serial number и т.п.) — ключ идемпотентности шлюза (P2-1). Nullable: не все
     * источники присылают его надёжно. UNIQUE среди непустых значений (V25) — гарантирует,
     * что повторная доставка с тем же идентификатором не создаёт вторую запись.
     */
    @Column(name = "external_message_id")
    private String externalMessageId;

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
    }
}
