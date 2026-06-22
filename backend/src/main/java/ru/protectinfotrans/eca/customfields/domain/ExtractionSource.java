package ru.protectinfotrans.eca.customfields.domain;

/**
 * Источник извлечения значения custom field из входящего сообщения — паритет с SITA Sequencer
 * (custom field правило ссылается либо на текст сообщения, либо на структурированный атрибут).
 */
public enum ExtractionSource {

    /**
     * Извлечение по regex с РОВНО ОДНОЙ capturing-группой из {@code IncomingMessage#content}
     * (свободный текст сообщения). {@link CustomFieldRule#getPattern()} — regex
     * ({@link java.util.regex.Pattern}), первая группа — извлекаемое значение.
     */
    CONTENT,

    /**
     * Извлечение по ключу из {@code IncomingMessage#metadataJson} (структурированные атрибуты
     * входящего — то, что внешняя система/парсер уже разложил по полям: координаты, ETA и т.п.).
     * {@link CustomFieldRule#getPattern()} — имя ключа в JSON-карте metadata (не regex).
     */
    METADATA
}
