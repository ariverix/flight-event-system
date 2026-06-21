package ru.protectinfotrans.eca.integration.domain;

/**
 * Статусная модель DLQ-записи (P2-6) — сбойное входящее сообщение, которое не удалось
 * разобрать/обработать на приёме.
 *
 * <p>{@code NEW}: попало в DLQ, ждёт ручного решения оператора. {@code REPROCESSED}: повторный
 * прогон через {@code RawMessageParserService}/{@code MessageInputPort} завершился успехом —
 * терминальный статус, сообщение в итоге попало в основной конвейер (см.
 * {@code DeadLetterMessage#reprocessedMessageId}). {@code DISCARDED}: оператор решил, что
 * сообщение не нужно (битый источник/дубль/тестовые данные) — терминальный статус, ручное решение
 * не предполагает дальнейшего автоматического реванша.
 */
public enum DeadLetterStatus {
    NEW,
    REPROCESSED,
    DISCARDED
}
