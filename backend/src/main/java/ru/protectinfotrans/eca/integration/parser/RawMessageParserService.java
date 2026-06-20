package ru.protectinfotrans.eca.integration.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Диспетчер парсинга сырых сообщений «борт-земля» по явно заданному формату (P2-2).
 *
 * <p>Единственная публичная точка входа в парсинг для других модулей — собирает все
 * реализации {@link MessageParser} (по одной на {@link RawMessageFormat}) и выбирает нужную.
 * Формат указывается ВЫЗЫВАЮЩЕЙ стороной явно (см. {@link RawMessageFormat} — обоснование
 * отказа от авто-детекта по структуре текста).
 */
@Service
@Slf4j
public class RawMessageParserService {

    private final Map<RawMessageFormat, MessageParser> parsersByFormat;

    public RawMessageParserService(List<MessageParser> parsers) {
        this.parsersByFormat = parsers.stream()
                .collect(Collectors.toUnmodifiableMap(MessageParser::supportedFormat, Function.identity()));
    }

    /**
     * Разобрать сырое сообщение в указанном формате.
     *
     * @param format     заявленный формат сообщения
     * @param rawMessage сырой текст
     * @return нормализованная структура (борт/рейс/тип/payload/externalMessageId/metadata)
     * @throws MessageParsingException если сообщение не соответствует структуре заявленного
     *         формата — НЕ теряем сообщение молча, вызывающая сторона (REST-адаптер) реагирует
     *         явной ошибкой (задел под DLQ, P2-6)
     * @throws IllegalArgumentException если для заявленного формата нет реализации (программная
     *         ошибка конфигурации — не должна происходить в проде, все {@link RawMessageFormat}
     *         покрыты {@link MessageParser}-реализациями)
     */
    public ParsedMessage parse(RawMessageFormat format, String rawMessage) {
        MessageParser parser = parsersByFormat.get(format);
        if (parser == null) {
            throw new IllegalStateException("Нет зарегистрированного парсера для формата " + format);
        }

        try {
            ParsedMessage parsed = parser.parse(rawMessage);
            log.info("Raw message parsed: format={}, aircraft={}, flight={}, template={}",
                    format, parsed.aircraftId(), parsed.flightNumber(), parsed.templateName());
            return parsed;
        } catch (MessageParsingException parsingFailed) {
            // НЕ теряем сообщение молча — лог на уровне ERROR с сырым телом (задел под DLQ, P2-6:
            // тут точка, где сырое сообщение + причина сбоя должны попасть в dead-letter очередь
            // для ручного reprocess, вместо отказа без следа).
            log.error("Raw message parsing failed: format={}, reason={}, rawMessage={}",
                    format, parsingFailed.getMessage(), rawMessage);
            throw parsingFailed;
        }
    }
}
