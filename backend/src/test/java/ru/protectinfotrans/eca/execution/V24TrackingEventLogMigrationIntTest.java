package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.domain.TrackingEventType;
import ru.protectinfotrans.eca.execution.port.out.TrackingEventLogPort;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-8 (часть 1 — схема): проверяет миграцию V24 — Event Log класса Tracking (SITA):
 * флаг {@code sequences.logging_enabled} (включение/выключение записи журнала per-sequence)
 * и отдельная таблица {@code tracking_event_log} (старт/стоп последовательности, завершение
 * шагов), отдельная от {@code step_executions} (V5, техническая история ОДНОГО инстанса)
 * и {@code audit_log} (V7/V20, действия пользователя).
 *
 * <p>Логика самой ЗАПИСИ событий (старт/стоп/завершение шага) — часть 2
 * (observability-agent), здесь покрывается только схема + entity-маппинг.
 *
 * Покрывает:
 *  - Flyway применяет V24 на чистой и на заполненной демо-данными БД (V9/V14)
 *    через resetDatabase() в BaseIntegrationTest (flyway.clean()+migrate() перед каждым тестом).
 *  - sequences.logging_enabled существует, NOT NULL, бэкафиллен значением true.
 *  - Таблица tracking_event_log существует с ожидаемыми колонками.
 *  - Все 4 индекса (sequence_id; aircraft_id+flight_number; instance_id; created_at) созданы.
 *  - Entity Sequence читает/пишет loggingEnabled через JPA.
 *  - Entity TrackingEventLog сохраняет/читает полную запись (STEP_COMPLETED) и минимальную
 *    запись без instance_id/flight_number (SEQUENCE_STARTED) через JPA-репозиторий (round-trip).
 */
@DisplayName("V24 sequences.logging_enabled + tracking_event_log: Event Log класса Tracking")
class V24TrackingEventLogMigrationIntTest extends BaseIntegrationTest {

    @Autowired
    private SequenceRepositoryPort sequenceRepository;

    @Autowired
    private TrackingEventLogPort trackingEventLogRepository;

    @Test
    @DisplayName("information_schema содержит NOT NULL колонку sequences.logging_enabled типа boolean")
    void migrationAddsLoggingEnabledColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_name = 'sequences'
                  AND column_name = 'logging_enabled'
                """);

        assertThat(columns).hasSize(1);
        Map<String, Object> column = columns.get(0);
        assertThat(column.get("data_type")).isEqualTo("boolean");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat((String) column.get("column_default")).contains("true");
    }

    @Test
    @DisplayName("существующие (демо V9/V14) последовательности бэкафиллены logging_enabled = true")
    void existingSequencesBackfilledWithLoggingEnabledTrue() {
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sequences", Integer.class);
        Integer loggingEnabledCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sequences WHERE logging_enabled = true", Integer.class);

        assertThat(totalCount).isGreaterThan(0);
        assertThat(loggingEnabledCount).isEqualTo(totalCount);
    }

    @Test
    @DisplayName("таблица tracking_event_log создана с ожидаемыми колонками")
    void migrationCreatesTrackingEventLogTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'tracking_event_log'
                """);

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder(
                "id", "sequence_id", "instance_id", "aircraft_id", "flight_number",
                "event_type", "step_index", "step_result", "details_json",
                "correlation_id", "created_at"
        );

        Map<String, String> nullability = columns.stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> (String) c.get("column_name"), c -> (String) c.get("is_nullable")));
        assertThat(nullability.get("sequence_id")).isEqualTo("NO");
        assertThat(nullability.get("aircraft_id")).isEqualTo("NO");
        assertThat(nullability.get("event_type")).isEqualTo("NO");
        assertThat(nullability.get("created_at")).isEqualTo("NO");
        assertThat(nullability.get("instance_id")).isEqualTo("YES");
        assertThat(nullability.get("flight_number")).isEqualTo("YES");
        assertThat(nullability.get("step_index")).isEqualTo("YES");
        assertThat(nullability.get("step_result")).isEqualTo("YES");
        assertThat(nullability.get("correlation_id")).isEqualTo("YES");
    }

    @Test
    @DisplayName("индексы под чтение журнала оператором созданы: по sequence_id, (aircraft_id, flight_number), instance_id, created_at")
    void migrationCreatesExpectedIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'tracking_event_log'
                """, String.class);

        assertThat(indexNames).containsExactlyInAnyOrder(
                "tracking_event_log_pkey",
                "idx_tracking_event_log_sequence",
                "idx_tracking_event_log_aircraft",
                "idx_tracking_event_log_instance",
                "idx_tracking_event_log_created_at"
        );

        String aircraftIndexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'tracking_event_log' AND indexname = 'idx_tracking_event_log_aircraft'
                """, String.class);
        assertThat(aircraftIndexDef).contains("aircraft_id").contains("flight_number");
    }

    @Test
    @DisplayName("Sequence entity сохраняет и читает loggingEnabled через JPA (дефолт true)")
    void sequenceEntityPersistsLoggingEnabledFlag() {
        Sequence sequence = Sequence.builder()
                .name("V24 test sequence")
                .status(SequenceStatus.DRAFT)
                .build();

        Sequence saved = sequenceRepository.save(sequence);
        assertThat(saved.isLoggingEnabled()).isTrue();

        Sequence reloaded = sequenceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.isLoggingEnabled()).isTrue();

        reloaded.setLoggingEnabled(false);
        Sequence updated = sequenceRepository.save(reloaded);
        assertThat(updated.isLoggingEnabled()).isFalse();

        Sequence reloadedAfterUpdate = sequenceRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloadedAfterUpdate.isLoggingEnabled()).isFalse();
    }

    @Test
    @DisplayName("TrackingEventLog сохраняет и читает полную запись STEP_COMPLETED через JPA")
    void savesAndReadsFullStepCompletedEvent() {
        String correlationId = UUID.randomUUID().toString();

        TrackingEventLog event = TrackingEventLog.builder()
                .sequenceId(1L)
                .instanceId(100L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .eventType(TrackingEventType.STEP_COMPLETED)
                .stepIndex(2)
                .stepResult(StepResult.SUCCESS)
                .detailsJson("{\"note\":\"test\"}")
                .correlationId(correlationId)
                .build();

        TrackingEventLog saved = trackingEventLogRepository.save(event);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        TrackingEventLog reloaded = jpaFind(saved.getId());
        assertThat(reloaded.getSequenceId()).isEqualTo(1L);
        assertThat(reloaded.getInstanceId()).isEqualTo(100L);
        assertThat(reloaded.getAircraftId()).isEqualTo("VP-BQR");
        assertThat(reloaded.getFlightNumber()).isEqualTo("SU1234");
        assertThat(reloaded.getEventType()).isEqualTo(TrackingEventType.STEP_COMPLETED);
        assertThat(reloaded.getStepIndex()).isEqualTo(2);
        assertThat(reloaded.getStepResult()).isEqualTo(StepResult.SUCCESS);
        assertThat(reloaded.getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("TrackingEventLog сохраняет минимальную запись SEQUENCE_STARTED без instance_id/flight_number/step_*")
    void savesMinimalSequenceStartedEventWithoutInstanceId() {
        TrackingEventLog event = TrackingEventLog.builder()
                .sequenceId(1L)
                .aircraftId("VP-BQR")
                .eventType(TrackingEventType.SEQUENCE_STARTED)
                .build();

        TrackingEventLog saved = trackingEventLogRepository.save(event);
        assertThat(saved.getId()).isNotNull();

        TrackingEventLog reloaded = jpaFind(saved.getId());
        assertThat(reloaded.getInstanceId()).isNull();
        assertThat(reloaded.getFlightNumber()).isNull();
        assertThat(reloaded.getStepIndex()).isNull();
        assertThat(reloaded.getStepResult()).isNull();
        assertThat(reloaded.getCorrelationId()).isNull();
        assertThat(reloaded.getEventType()).isEqualTo(TrackingEventType.SEQUENCE_STARTED);
    }

    @Autowired
    private ru.protectinfotrans.eca.execution.adapter.out.persistence.TrackingEventLogJpaRepository jpaRepository;

    /** Перечитывает запись через JPA-репозиторий (а не raw SQL), чтобы покрыть маппинг entity. */
    private TrackingEventLog jpaFind(Long id) {
        return jpaRepository.findById(id).orElseThrow();
    }
}
