package ru.protectinfotrans.eca.customfields.port.in;

import ru.protectinfotrans.eca.MessageType;

import java.util.Map;

/**
 * Входной порт извлечения custom fields из ОДНОГО входящего сообщения — вызывается
 * {@code eventprocessor} (см. {@code MessagePersistenceTransaction#persistAndPublish}) сразу
 * после того, как сообщение persist'ится, с уже известными скалярами этого сообщения.
 *
 * <p><b>Почему скаляры, а не {@code IncomingMessage} целиком:</b> customfields — модуль-СИНК (как
 * {@code templates}), он не должен Java-импортировать домен {@code eventprocessor} (см. javadoc
 * пакета {@code ru.protectinfotrans.eca.customfields}, секция "границы Modulith") — иначе
 * {@code eventprocessor} (вызывающий) и {@code customfields} (вызываемый) образовали бы цикл, если
 * бы второй модуль импортировал тип первого. Передача только примитивов/строк полностью устраняет
 * необходимость такого импорта при сохранении той же информации, нужной для применения правил.
 */
public interface CustomFieldExtractionUseCase {

    /**
     * Применяет все активные правила, подходящие по {@code messageType} (и опционально
     * {@code templateName}), к данному входящему сообщению; для каждого совпавшего правила
     * извлечённое значение перезаписывает текущее per-flight значение поля
     * {@code (aircraftId, flightNumber, fieldName)} — см. {@code CustomFieldValue} javadoc
     * ("текущее значение, не история").
     *
     * <p>Не бросает исключений при отсутствии совпадений/несовпадении паттерна — отсутствие
     * экстракции для конкретного сообщения является штатной (а не ошибочной) ситуацией: не каждое
     * входящее сообщение обязано нести каждое custom field.
     *
     * @param sourceMessageId {@code IncomingMessage#id} — для трассировки ({@code CustomFieldValue#sourceMessageId})
     * @param messageType тип входящего сообщения (DOWNLINK/UPLINK/GROUND)
     * @param templateName имя шаблона, под которым классифицировано сообщение (может быть {@code null})
     * @param aircraftId идентификатор ВС — часть per-flight ключа; если {@code null}, экстракция не
     *                    выполняется (нет рейса, к которому привязать значение)
     * @param flightNumber номер рейса — часть per-flight ключа
     * @param content текст сообщения — источник для правил {@code ExtractionSource.CONTENT}
     * @param metadata метаданные сообщения — источник для правил {@code ExtractionSource.METADATA}
     */
    void extract(
            Long sourceMessageId,
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata
    );
}
