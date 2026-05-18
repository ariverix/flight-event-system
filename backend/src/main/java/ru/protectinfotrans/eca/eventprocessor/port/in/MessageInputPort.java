package ru.protectinfotrans.eca.eventprocessor.port.in;

import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;

import java.util.Map;

/**
 * Входной порт для приёма сообщений от внешних систем.
 *
 * вызывают этот порт через REST-адаптер для отправки сообщений.
 *
 */
public interface MessageInputPort {

    /**
     * Принять входящее сообщение от внешней системы.
     *
     * @param messageType тип сообщения (DOWNLINK, UPLINK, GROUND)
     * @param templateName имя шаблона сообщения
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @param content содержимое сообщения
     * @param metadata дополнительные метаданные
     * @return ID сохранённого сообщения
     */
    Long receiveMessage(
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata
    );

    /**
     * Уведомить об изменении стадии полёта.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @param stage новая стадия полёта
     */
    void notifyFlightStageChange(
            String aircraftId,
            String flightNumber,
            FlightStage stage
    );
}
