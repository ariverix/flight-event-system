package ru.protectinfotrans.eca.integration.parser;

/**
 * Парсер одного формата сообщения «борт-земля» в нормализованную структуру.
 * Одна реализация на формат (ARINC 618/620, Type B, AFTN) — см. {@code integration.parser.format}.
 */
public interface MessageParser {

    /**
     * Формат, который умеет разбирать данная реализация.
     */
    RawMessageFormat supportedFormat();

    /**
     * Разобрать сырое текстовое сообщение.
     *
     * @param rawMessage сырой текст телеграммы/ACARS-сообщения, как получен от внешней системы
     * @return нормализованная структура (борт/рейс/тип/payload/externalMessageId/metadata)
     * @throws MessageParsingException если сообщение битое/неполное/не соответствует формату —
     *         НЕ теряем сообщение молча, вызывающая сторона решает, как реагировать (сейчас — 400,
     *         после P2-6 — DLQ)
     */
    ParsedMessage parse(String rawMessage);
}
