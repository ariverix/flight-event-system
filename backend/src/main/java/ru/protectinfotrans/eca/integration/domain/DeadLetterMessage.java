package ru.protectinfotrans.eca.integration.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * P2-6: DLQ-запись сбойного входящего сообщения «борт-земля» — раз сообщение НЕ удалось
 * разобрать/обработать на приёме (см. {@code RawMessageController#receiveRawMessage}), оно
 * не теряется молча (как было до P2-6, только ERROR-лог в {@code RawMessageParserService}), а
 * персистится здесь вместе с сырым телом, форматом и причиной сбоя — для дальнейшего ручного
 * {@code reprocess} оператором ({@code DeadLetterQueueService#reprocess}).
 *
 * <p><b>"Сначала persist, потом обработка" для DLQ (CLAUDE.md, "Жёсткие правила"):</b> запись в
 * эту таблицу — это и есть "persist" для сообщения, которое обработка (парсинг) отвергла; запись
 * коммитится в СОБСТВЕННОЙ, отдельной транзакции (см. {@code DeadLetterQueueService#captureFailure},
 * {@code REQUIRES_NEW}) НЕЗАВИСИМО от того, что происходит дальше с HTTP-ответом — то же свойство
 * "не терять оригинал на сбое", что у {@code MessagePersistenceTransaction} (P1-7) и
 * {@code OutboundMessage} (P2-3).
 *
 * <p>Без FK на что-либо — DLQ-запись самодостаточна (несёт весь контекст, нужный для повторного
 * прогона), переживает любые изменения схемы execution/sequence, тот же принцип "без FK", что у
 * {@link OutboundMessage} (см. её javadoc).
 */
@Entity
@Table(name = "dead_letter_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeadLetterMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private DeadLetterSource source;

    /** Формат сырого сообщения, если известен (всегда известен для {@link DeadLetterSource#RAW_GATEWAY}). */
    @Column(name = "format", length = 20)
    private String format;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;

    /** Доп. контекст приёма (departureAirport/arrivalAirport/flightDate из {@code RawIncomingMessageRequest}), JSON. */
    @Column(name = "request_context", columnDefinition = "TEXT")
    private String requestContext;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatus status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    /** ID итогового {@code IncomingMessage}, если reprocess завершился успехом (status=REPROCESSED). */
    @Column(name = "reprocessed_message_id")
    private Long reprocessedMessageId;

    /** Сквозной идентификатор для Event Log/корреляции по борту/рейсу. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = DeadLetterStatus.NEW;
        }
        if (attempts == null) {
            attempts = 0;
        }
    }
}
