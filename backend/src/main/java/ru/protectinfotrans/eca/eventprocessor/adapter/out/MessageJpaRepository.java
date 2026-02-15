package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;

import java.time.LocalDateTime;

/**
 * Spring Data JPA репозиторий для входящих сообщений.
 */
public interface MessageJpaRepository extends JpaRepository<IncomingMessage, Long> {

    /**
     * Проверка существования сообщения по типу, шаблону и ВС.
     * Опционально фильтр по времени (для fromThisPointOnly в WAIT-шагах).
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.messageType = :messageType " +
           "AND m.templateName = :templateName " +
           "AND (:afterTime IS NULL OR m.receivedAt > :afterTime)")
    boolean existsByAircraftAndTypeAndTemplate(
            @Param("aircraftId") String aircraftId,
            @Param("messageType") MessageType messageType,
            @Param("templateName") String templateName,
            @Param("afterTime") LocalDateTime afterTime
    );

    /**
     * Проверка позиционного отчёта за последние N минут.
     * Позиционный отчёт — DOWNLINK с templateName содержащим "POSITION" или "POS".
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.messageType = 'DOWNLINK' " +
           "AND (m.templateName LIKE '%POSITION%' OR m.templateName LIKE '%POS%') " +
           "AND m.receivedAt >= :sinceTime")
    boolean existsPositionReportWithinMinutes(
            @Param("aircraftId") String aircraftId,
            @Param("sinceTime") LocalDateTime sinceTime
    );

    /**
     * Поиск сообщений по ВС с пагинацией.
     */
    Page<IncomingMessage> findByAircraftId(String aircraftId, Pageable pageable);

    /**
     * Поиск сообщений по типу с пагинацией.
     */
    Page<IncomingMessage> findByMessageType(MessageType messageType, Pageable pageable);

    /**
     * Поиск сообщений по ВС и типу с пагинацией.
     */
    Page<IncomingMessage> findByAircraftIdAndMessageType(String aircraftId, MessageType messageType, Pageable pageable);
}
