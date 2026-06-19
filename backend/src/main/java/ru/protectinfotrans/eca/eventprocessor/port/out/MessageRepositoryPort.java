package ru.protectinfotrans.eca.eventprocessor.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Выходной порт для хранения входящих сообщений.
 *
 */
public interface MessageRepositoryPort {

    IncomingMessage save(IncomingMessage message);

    Optional<IncomingMessage> findById(Long id);

    /**
     * Получить список сообщений с фильтрами и пагинацией.
     *
     * @param aircraftId фильтр по ВС (null = без фильтра)
     * @param messageType фильтр по типу сообщения (null = без фильтра)
     * @param pageable параметры пагинации
     * @return страница сообщений
     */
    Page<IncomingMessage> findAllWithFilters(String aircraftId, MessageType messageType, Pageable pageable);

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
     * Проверка получения ФАКТИЧЕСКОГО (не estimated) позиционного отчёта за последние N минут —
     * паритет с SITA Sequencer: оценочные позиции игнорируются POSITION-критерием.
     *
     * Позиционный отчёт — сообщение с непустым positionSource (ACARS/RADAR/ADS_B).
     *
     * @param aircraftId идентификатор ВС
     * @param sinceTime нижняя граница окна (now - x минут)
     * @param source источник отчёта (null = любой источник)
     * @param afterTime для "from this point only": учитывать только отчёты после этого времени
     *                  (null = учитывать всю историю в пределах окна sinceTime)
     * @return true если фактический позиционный отчёт найден
     */
    boolean existsActualPositionReportSince(
            String aircraftId,
            LocalDateTime sinceTime,
            PositionSource source,
            LocalDateTime afterTime
    );

    /**
     * Момент последнего ФАКТИЧЕСКОГО позиционного отчёта по ВС (для диагностики/"not reported").
     *
     * @param aircraftId идентификатор ВС
     * @param source источник отчёта (null = любой источник)
     * @return время последнего фактического отчёта, либо empty если отчётов не было вовсе
     */
    Optional<LocalDateTime> findLastActualPositionReportTime(String aircraftId, PositionSource source);
}
