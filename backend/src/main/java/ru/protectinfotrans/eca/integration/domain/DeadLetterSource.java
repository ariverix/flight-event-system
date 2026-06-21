package ru.protectinfotrans.eca.integration.domain;

/**
 * Откуда сбойное сообщение попало в DLQ (P2-6) — определяет, КАК ручной reprocess повторно
 * прогоняет {@link DeadLetterMessage#getRawPayload()} через конвейер приёма.
 */
public enum DeadLetterSource {
    /** Сырое сообщение с {@code POST /api/v1/messages/incoming/raw} — формат известен ({@link DeadLetterMessage#getFormat()}), reprocess идёт через {@code RawMessageParserService}. */
    RAW_GATEWAY,
    /** Уже структурированное сообщение с {@code POST /api/v1/messages/incoming} — сбой произошёл не на парсинге формата, а на самом приёме/публикации события. */
    STRUCTURED_GATEWAY
}
