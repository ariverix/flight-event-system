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

    // workaround: PostgreSQL 42P18 (indeterminate datatype) при null-параметре в IS NULL —
    // два отдельных метода вместо одного с nullable afterTime
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.messageType = :messageType " +
           "AND m.templateName = :templateName")
    boolean existsByAircraftAndTypeAndTemplateAnyTime(
            @Param("aircraftId") String aircraftId,
            @Param("messageType") MessageType messageType,
            @Param("templateName") String templateName
    );

    // вариант с afterTime — для fromThisPointOnly=true в WAIT-шагах
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.messageType = :messageType " +
           "AND m.templateName = :templateName " +
           "AND m.receivedAt > :afterTime")
    boolean existsByAircraftAndTypeAndTemplateAfter(
            @Param("aircraftId") String aircraftId,
            @Param("messageType") MessageType messageType,
            @Param("templateName") String templateName,
            @Param("afterTime") LocalDateTime afterTime
    );

    // NOTE: "POSITION" и "POS" — эвристика, в проде нужен enum шаблонов или отдельный тип
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.messageType = 'DOWNLINK' " +
           "AND (m.templateName LIKE '%POSITION%' OR m.templateName LIKE '%POS%') " +
           "AND m.receivedAt >= :sinceTime")
    boolean existsPositionReportWithinMinutes(
            @Param("aircraftId") String aircraftId,
            @Param("sinceTime") LocalDateTime sinceTime
    );

    Page<IncomingMessage> findByAircraftId(String aircraftId, Pageable pageable);
    Page<IncomingMessage> findByMessageType(MessageType messageType, Pageable pageable);
    Page<IncomingMessage> findByAircraftIdAndMessageType(String aircraftId, MessageType messageType, Pageable pageable);
}
