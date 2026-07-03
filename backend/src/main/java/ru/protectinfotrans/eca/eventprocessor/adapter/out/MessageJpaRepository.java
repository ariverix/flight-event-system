package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Optional;

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

    // Позиционный отчёт идентифицируется по непустому positionSource (не по эвристике имени шаблона) —
    // estimatedPosition=false исключает оценочные позиции из любого источника (паритет с SITA).
    // afterTime nullable: для PostgreSQL 42P18 нужны два отдельных метода, как и для message-критерия выше.
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.positionSource IS NOT NULL " +
           "AND m.estimatedPosition = false " +
           "AND (:source IS NULL OR m.positionSource = :source) " +
           "AND m.receivedAt >= :sinceTime")
    boolean existsActualPositionReportSinceAnyPoint(
            @Param("aircraftId") String aircraftId,
            @Param("sinceTime") LocalDateTime sinceTime,
            @Param("source") PositionSource source
    );

    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.positionSource IS NOT NULL " +
           "AND m.estimatedPosition = false " +
           "AND (:source IS NULL OR m.positionSource = :source) " +
           "AND m.receivedAt >= :sinceTime " +
           "AND m.receivedAt > :afterTime")
    boolean existsActualPositionReportSinceAfterPoint(
            @Param("aircraftId") String aircraftId,
            @Param("sinceTime") LocalDateTime sinceTime,
            @Param("source") PositionSource source,
            @Param("afterTime") LocalDateTime afterTime
    );

    @Query("SELECT MAX(m.receivedAt) FROM IncomingMessage m " +
           "WHERE m.aircraftId = :aircraftId " +
           "AND m.positionSource IS NOT NULL " +
           "AND m.estimatedPosition = false " +
           "AND (:source IS NULL OR m.positionSource = :source)")
    Optional<LocalDateTime> findLastActualPositionReportTime(
            @Param("aircraftId") String aircraftId,
            @Param("source") PositionSource source
    );

    Page<IncomingMessage> findByAircraftId(String aircraftId, Pageable pageable);
    Page<IncomingMessage> findByMessageType(MessageType messageType, Pageable pageable);
    Page<IncomingMessage> findByAircraftIdAndMessageType(String aircraftId, MessageType messageType, Pageable pageable);

    // P2-1: lookup идемпотентности шлюза по идентификатору внешней ACARS-системы.
    Optional<IncomingMessage> findByExternalMessageId(String externalMessageId);

    // ---------------------------------------------------------------
    // Фаза 5: проекция «список бортов» (GROUP BY aircraft_id) для UI aircraft-bindings.
    // Отдельные методы с/без поиска — тот же приём, что и findAllWithFilters (избегаем
    // nullable-параметра). Явный countQuery = число различных бортов (групп), а не строк.
    // ORDER BY MAX(receivedAt) — самый недавно активный борт сверху; Pageable без Sort.
    // ---------------------------------------------------------------
    @Query(value = "SELECT new ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse("
            + "m.aircraftId, MAX(m.receivedAt), COUNT(m), COUNT(DISTINCT m.flightNumber)) "
            + "FROM IncomingMessage m WHERE m.aircraftId IS NOT NULL "
            + "GROUP BY m.aircraftId ORDER BY MAX(m.receivedAt) DESC",
            countQuery = "SELECT COUNT(DISTINCT m.aircraftId) FROM IncomingMessage m WHERE m.aircraftId IS NOT NULL")
    Page<AircraftSummaryResponse> findAircraftSummaries(Pageable pageable);

    @Query(value = "SELECT new ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse("
            + "m.aircraftId, MAX(m.receivedAt), COUNT(m), COUNT(DISTINCT m.flightNumber)) "
            + "FROM IncomingMessage m WHERE m.aircraftId IS NOT NULL "
            + "AND LOWER(m.aircraftId) LIKE LOWER(CONCAT('%', :search, '%')) "
            + "GROUP BY m.aircraftId ORDER BY MAX(m.receivedAt) DESC",
            countQuery = "SELECT COUNT(DISTINCT m.aircraftId) FROM IncomingMessage m "
                    + "WHERE m.aircraftId IS NOT NULL "
                    + "AND LOWER(m.aircraftId) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<AircraftSummaryResponse> searchAircraftSummaries(@Param("search") String search, Pageable pageable);
}
