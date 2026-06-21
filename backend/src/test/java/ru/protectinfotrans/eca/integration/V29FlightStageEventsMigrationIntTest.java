package ru.protectinfotrans.eca.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.eventprocessor.adapter.out.FlightStageEventJpaRepository;
import ru.protectinfotrans.eca.eventprocessor.domain.FlightStageEvent;
import ru.protectinfotrans.eca.eventprocessor.port.out.FlightStageEventRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-5: проверяет миграцию V29 — durable журнал смен стадии полёта {@code flight_stage_events},
 * который служит источником истины Off-таймстампа для POSITION-критерия "not reported"
 * (CriterionEvaluator#evaluatePosition, паритет SITA Sequencer).
 *
 * Покрывает:
 *  - Flyway применяет V29 на чистой БД (resetDatabase() в BaseIntegrationTest).
 *  - Таблица flight_stage_events существует с ожидаемыми колонками и nullability.
 *  - Индекс под горячий запрос (aircraft_id, stage, occurred_at) создан.
 *  - FlightStageEvent сохраняет/читает запись через JPA.
 *  - FlightStageEventRepositoryPort#findLastStageTimestamp возвращает САМЫЙ ПОЗДНИЙ occurred_at
 *    для (aircraft_id, stage), если их несколько (борт взлетал несколько раз за разные рейсы).
 *  - findLastStageTimestamp возвращает empty, если для борта нет записей искомой стадии.
 */
@DisplayName("V29 flight_stage_events: durable журнал OOOI-стадий для Off-таймстампа (P2-5)")
class V29FlightStageEventsMigrationIntTest extends BaseIntegrationTest {

    @Autowired
    private FlightStageEventRepositoryPort flightStageEventRepository;

    @Autowired
    private FlightStageEventJpaRepository jpaRepository;

    @Test
    @DisplayName("таблица flight_stage_events создана с ожидаемыми колонками и nullability")
    void migrationCreatesFlightStageEventsTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'flight_stage_events'
                """);

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder(
                "id", "aircraft_id", "flight_number", "stage", "occurred_at", "created_at"
        );

        Map<String, String> nullability = columns.stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> (String) c.get("column_name"), c -> (String) c.get("is_nullable")));

        assertThat(nullability.get("aircraft_id")).isEqualTo("NO");
        assertThat(nullability.get("stage")).isEqualTo("NO");
        assertThat(nullability.get("occurred_at")).isEqualTo("NO");
        assertThat(nullability.get("created_at")).isEqualTo("NO");
        assertThat(nullability.get("flight_number")).isEqualTo("YES");
    }

    @Test
    @DisplayName("индекс под горячий запрос (aircraft_id, stage, occurred_at) создан")
    void migrationCreatesLookupIndex() {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'flight_stage_events'
                """, String.class);

        assertThat(indexNames).containsExactlyInAnyOrder(
                "flight_stage_events_pkey",
                "idx_flight_stage_events_lookup"
        );

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'flight_stage_events' AND indexname = 'idx_flight_stage_events_lookup'
                """, String.class);
        assertThat(indexDef).contains("aircraft_id").contains("stage").contains("occurred_at");
    }

    @Test
    @DisplayName("FlightStageEvent сохраняет и читает запись через JPA")
    void savesAndReadsFlightStageEvent() {
        FlightStageEvent event = FlightStageEvent.builder()
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .stage(FlightStage.OFF)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 10, 30))
                .build();

        flightStageEventRepository.save(event);

        List<FlightStageEvent> all = jpaRepository.findAll();
        assertThat(all).hasSize(1);
        FlightStageEvent reloaded = all.get(0);
        assertThat(reloaded.getId()).isNotNull();
        assertThat(reloaded.getAircraftId()).isEqualTo("VP-BQR");
        assertThat(reloaded.getFlightNumber()).isEqualTo("SU1234");
        assertThat(reloaded.getStage()).isEqualTo(FlightStage.OFF);
        assertThat(reloaded.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 6, 21, 10, 30));
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findLastStageTimestamp возвращает самый поздний occurred_at, если борт взлетал несколько раз")
    void findLastStageTimestampReturnsLatestAmongMultipleOffEvents() {
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VP-BQR").flightNumber("SU1234")
                .stage(FlightStage.OFF)
                .occurredAt(LocalDateTime.of(2026, 6, 20, 8, 0))
                .build());
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VP-BQR").flightNumber("SU5678")
                .stage(FlightStage.OFF)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 10, 30))
                .build());
        // другая стадия того же борта не должна перепутаться с OFF
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VP-BQR").flightNumber("SU5678")
                .stage(FlightStage.ON)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 23, 0))
                .build());

        Optional<LocalDateTime> result =
                flightStageEventRepository.findLastStageTimestamp("VP-BQR", FlightStage.OFF);

        assertThat(result).contains(LocalDateTime.of(2026, 6, 21, 10, 30));
    }

    @Test
    @DisplayName("findLastStageTimestamp возвращает empty, если для борта нет записей искомой стадии")
    void findLastStageTimestampReturnsEmptyWhenNoMatchingStage() {
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VP-BQR").flightNumber("SU1234")
                .stage(FlightStage.ON)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 10, 30))
                .build());

        Optional<LocalDateTime> result =
                flightStageEventRepository.findLastStageTimestamp("VP-BQR", FlightStage.OFF);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findLastStageTimestamp не путает разные борта")
    void findLastStageTimestampDoesNotMixDifferentAircraft() {
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VP-BQR").flightNumber("SU1234")
                .stage(FlightStage.OFF)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 10, 30))
                .build());
        flightStageEventRepository.save(FlightStageEvent.builder()
                .aircraftId("VQ-OTHER").flightNumber("SU9999")
                .stage(FlightStage.OFF)
                .occurredAt(LocalDateTime.of(2026, 6, 21, 23, 0))
                .build());

        Optional<LocalDateTime> result =
                flightStageEventRepository.findLastStageTimestamp("VP-BQR", FlightStage.OFF);

        assertThat(result).contains(LocalDateTime.of(2026, 6, 21, 10, 30));
    }
}
