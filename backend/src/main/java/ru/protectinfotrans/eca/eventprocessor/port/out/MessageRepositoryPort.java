package ru.protectinfotrans.eca.eventprocessor.port.out;

import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Выходной порт для хранения входящих сообщений.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6
 */
public interface MessageRepositoryPort {

    IncomingMessage save(IncomingMessage message);

    Optional<IncomingMessage> findById(Long id);

    /**
     * Проверка получения сообщения определённого типа и шаблона для ВС.
     * Для WAIT-шагов с fromThisPointOnly=true: проверяет только сообщения с receivedAt > waitStartedAt.
     *
     * @param aircraftId идентификатор ВС
     * @param messageType тип сообщения (DOWNLINK/UPLINK/GROUND)
     * @param templateName имя шаблона сообщения
     * @param afterTime учитывать только сообщения после этого времени (null = все сообщения)
     * @return true если такое сообщение найдено
     */
    boolean existsByAircraftAndTypeAndTemplate(
            String aircraftId,
            MessageType messageType,
            String templateName,
            LocalDateTime afterTime
    );

    /**
     * Проверка получения позиционного отчёта за последние N минут.
     * Позиционный отчёт - это сообщение DOWNLINK с templateName содержащим "POSITION" или "POS".
     *
     * @param aircraftId идентификатор ВС
     * @param minutesAgo количество минут назад
     * @return true если позиционный отчёт был за указанный период
     */
    boolean existsPositionReportWithinMinutes(String aircraftId, int minutesAgo);
}
