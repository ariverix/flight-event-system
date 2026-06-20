package ru.protectinfotrans.eca.integration.parser;

/**
 * Сообщение в заявленном формате не удалось разобрать (битое/неполное/не соответствует
 * структуре формата).
 *
 * <p>Намеренно НЕ {@link RuntimeException} напрямую без контекста — несёт формат и (если
 * известен) фрагмент сырого сообщения, чтобы вызывающая сторона (REST-адаптер) могла вернуть
 * структурированную 400-ошибку (через {@code GlobalExceptionHandler.handleBadRequest}, который
 * уже обрабатывает {@link IllegalArgumentException} — этот класс наследует его, поэтому
 * подключается без изменений в обработчике) вместо потери сообщения молча или 500.
 *
 * <p>Задел под DLQ (P2-6, не реализуется здесь): вызывающая сторона при перехвате этого
 * исключения — естественная точка, где сырое сообщение + причина сбоя должны попасть в
 * dead-letter очередь для ручного reprocess, вместо того чтобы быть отброшенными. Сейчас
 * (до P2-6) ошибка как минимум не теряется молча — логируется на ERROR с сырым телом и
 * возвращается как явный 400 вызывающей системе.
 */
public class MessageParsingException extends IllegalArgumentException {

    private final RawMessageFormat format;

    public MessageParsingException(RawMessageFormat format, String message) {
        super(message);
        this.format = format;
    }

    public MessageParsingException(RawMessageFormat format, String message, Throwable cause) {
        super(message, cause);
        this.format = format;
    }

    public RawMessageFormat getFormat() {
        return format;
    }
}
