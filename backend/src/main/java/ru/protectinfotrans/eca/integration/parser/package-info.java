/**
 * Парсеры форматов «борт-земля» (P2-2): ARINC 618/620, Type B, AFTN.
 *
 * <p>Зона ответственности — нормализация СЫРОГО текста телеграммы/ACARS-сообщения
 * в структуру {@link ru.protectinfotrans.eca.integration.parser.ParsedMessage}
 * (борт/рейс/тип/payload/externalMessageId), готовую для передачи на вход
 * {@code eventprocessor.port.in.MessageInputPort#receiveMessage} (P2-1: persist +
 * идемпотентность + публикация {@code NormalizedEvent} там уже реализованы и
 * переиспользуются без изменений).
 *
 * <p>Публичный named interface модуля — другие модули (в частности
 * {@code eventprocessor}, который физически вызывает парсинг из своего REST-адаптера
 * на raw-эндпоинте) обращаются СЮДА, а не во внутренние пакеты конкретных форматов
 * ({@code integration.parser.format}, который named interface не объявляет и закрыт
 * снаружи модуля).
 */
@org.springframework.modulith.NamedInterface("parser")
package ru.protectinfotrans.eca.integration.parser;
