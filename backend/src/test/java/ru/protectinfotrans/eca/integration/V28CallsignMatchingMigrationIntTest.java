package ru.protectinfotrans.eca.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.integration.adapter.out.persistence.CallsignMatchingJpaRepository;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;
import ru.protectinfotrans.eca.integration.port.out.CallsignMatchingRepositoryPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-4 (часть 1 — схема): проверяет миграцию V28 — таблица соответствия позывных flight id (FI)
 * {@code callsign_matching}, паритет SITA "callsign matching table" (CLAUDE.md: «ICAO code,
 * даты, дни недели, dep/arr airport»).
 *
 * <p>Алгоритм разбора позывного и выбор лучшего совпадения среди кандидатов (по specificity,
 * дню недели, номеру рейса) — часть 2 (integration-dev). Здесь покрывается только схема +
 * entity-маппинг + базовый запрос кандидатов {@link CallsignMatchingRepositoryPort#findCandidates},
 * на котором часть 2 строит итоговый матчинг.
 *
 * Покрывает:
 *  - Flyway применяет V28 на чистой и на заполненной демо-данными БД (через resetDatabase()
 *    в BaseIntegrationTest — flyway.clean()+migrate() перед каждым тестом).
 *  - Таблица callsign_matching существует с ожидаемыми колонками и nullability.
 *  - Все 3 индекса под матчинг-запрос созданы.
 *  - CHECK-констрейнт на days_of_week (только '0'/'1' длиной 7) работает.
 *  - Демо-правила V28 (AFL -> SU1234) видны через findCandidates.
 *  - Entity CallsignMatchingRule сохраняет/читает правило: полное (период, дни недели,
 *    аэропорты) и минимальное (все nullable поля = NULL, бессрочное/любой день/любой аэропорт).
 *  - findCandidates фильтрует по перевозчику, активности и периоду действия.
 */
@DisplayName("V28 callsign_matching: таблица соответствия позывных flight id (FI)")
class V28CallsignMatchingMigrationIntTest extends BaseIntegrationTest {

    @Autowired
    private CallsignMatchingRepositoryPort callsignMatchingRepository;

    @Autowired
    private CallsignMatchingJpaRepository jpaRepository;

    @Test
    @DisplayName("таблица callsign_matching создана с ожидаемыми колонками и nullability")
    void migrationCreatesCallsignMatchingTable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'callsign_matching'
                """);

        assertThat(columns).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder(
                "id", "icao_carrier_code", "flight_number", "flight_id",
                "valid_from", "valid_to", "days_of_week",
                "departure_airport", "arrival_airport",
                "specificity", "active", "created_at"
        );

        Map<String, String> nullability = columns.stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> (String) c.get("column_name"), c -> (String) c.get("is_nullable")));
        assertThat(nullability.get("icao_carrier_code")).isEqualTo("NO");
        assertThat(nullability.get("flight_id")).isEqualTo("NO");
        assertThat(nullability.get("specificity")).isEqualTo("NO");
        assertThat(nullability.get("active")).isEqualTo("NO");
        assertThat(nullability.get("created_at")).isEqualTo("NO");

        assertThat(nullability.get("flight_number")).isEqualTo("YES");
        assertThat(nullability.get("valid_from")).isEqualTo("YES");
        assertThat(nullability.get("valid_to")).isEqualTo("YES");
        assertThat(nullability.get("days_of_week")).isEqualTo("YES");
        assertThat(nullability.get("departure_airport")).isEqualTo("YES");
        assertThat(nullability.get("arrival_airport")).isEqualTo("YES");
    }

    @Test
    @DisplayName("индексы под матчинг-запрос созданы: по перевозчику, по (перевозчик+период), по аэропортам")
    void migrationCreatesExpectedIndexes() {
        List<String> indexNames = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'callsign_matching'
                """, String.class);

        assertThat(indexNames).containsExactlyInAnyOrder(
                "callsign_matching_pkey",
                "idx_callsign_matching_carrier",
                "idx_callsign_matching_carrier_period",
                "idx_callsign_matching_airports"
        );

        String periodIndexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'callsign_matching' AND indexname = 'idx_callsign_matching_carrier_period'
                """, String.class);
        assertThat(periodIndexDef).contains("icao_carrier_code").contains("valid_from").contains("valid_to");

        String airportsIndexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'callsign_matching' AND indexname = 'idx_callsign_matching_airports'
                """, String.class);
        assertThat(airportsIndexDef).contains("departure_airport").contains("arrival_airport");
    }

    @Test
    @DisplayName("CHECK-констрейнт days_of_week отвергает значение неверного формата")
    void daysOfWeekCheckConstraintRejectsInvalidValue() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                jdbcTemplate.execute("""
                        INSERT INTO callsign_matching
                            (icao_carrier_code, flight_id, days_of_week, specificity, active)
                        VALUES ('XXX', 'XX0000', 'invalid', 0, true)
                        """))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("демо-правила V28 (AFL -> SU1234, канонический сценарий) загружены и видны через findCandidates")
    void demoRulesAreLoadedAndVisibleViaFindCandidates() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM callsign_matching WHERE icao_carrier_code = 'AFL'", Integer.class);
        assertThat(count).isEqualTo(2);

        List<CallsignMatchingRule> candidates =
                callsignMatchingRepository.findCandidates("AFL", LocalDate.of(2026, 6, 22));

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(CallsignMatchingRule::getFlightId)
                .containsOnly("SU1234");
        assertThat(candidates).extracting(CallsignMatchingRule::getSpecificity)
                .containsExactlyInAnyOrder(0, 10);
    }

    @Test
    @DisplayName("CallsignMatchingRule сохраняет и читает полное правило (период, дни недели, аэропорты) через JPA")
    void savesAndReadsFullRule() {
        CallsignMatchingRule rule = CallsignMatchingRule.builder()
                .icaoCarrierCode("SVR")
                .flightNumber("5678")
                .flightId("U65678")
                .validFrom(LocalDate.of(2026, 1, 1))
                .validTo(LocalDate.of(2026, 12, 31))
                .daysOfWeek("1111100")
                .departureAirport("UUWW")
                .arrivalAirport("ULLI")
                .specificity(10)
                .active(true)
                .build();

        CallsignMatchingRule saved = callsignMatchingRepository.save(rule);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        CallsignMatchingRule reloaded = jpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getIcaoCarrierCode()).isEqualTo("SVR");
        assertThat(reloaded.getFlightNumber()).isEqualTo("5678");
        assertThat(reloaded.getFlightId()).isEqualTo("U65678");
        assertThat(reloaded.getValidFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(reloaded.getValidTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(reloaded.getDaysOfWeek()).isEqualTo("1111100");
        assertThat(reloaded.getDepartureAirport()).isEqualTo("UUWW");
        assertThat(reloaded.getArrivalAirport()).isEqualTo("ULLI");
        assertThat(reloaded.getSpecificity()).isEqualTo(10);
        assertThat(reloaded.getActive()).isTrue();
    }

    @Test
    @DisplayName("CallsignMatchingRule сохраняет минимальное правило: nullable поля = NULL (бессрочно/любой день/любой аэропорт), дефолты specificity=0 и active=true")
    void savesMinimalRuleWithDefaults() {
        CallsignMatchingRule rule = CallsignMatchingRule.builder()
                .icaoCarrierCode("BAW")
                .flightId("BA0001")
                .build();

        CallsignMatchingRule saved = callsignMatchingRepository.save(rule);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSpecificity()).isEqualTo(0);
        assertThat(saved.getActive()).isTrue();

        CallsignMatchingRule reloaded = jpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFlightNumber()).isNull();
        assertThat(reloaded.getValidFrom()).isNull();
        assertThat(reloaded.getValidTo()).isNull();
        assertThat(reloaded.getDaysOfWeek()).isNull();
        assertThat(reloaded.getDepartureAirport()).isNull();
        assertThat(reloaded.getArrivalAirport()).isNull();
    }

    @Test
    @DisplayName("findCandidates не возвращает правило за пределами периода действия (valid_to в прошлом)")
    void findCandidatesExcludesExpiredRule() {
        callsignMatchingRepository.save(CallsignMatchingRule.builder()
                .icaoCarrierCode("EZY")
                .flightId("U2EXPIRED")
                .validFrom(LocalDate.of(2020, 1, 1))
                .validTo(LocalDate.of(2020, 12, 31))
                .specificity(0)
                .active(true)
                .build());

        List<CallsignMatchingRule> candidates =
                callsignMatchingRepository.findCandidates("EZY", LocalDate.of(2026, 6, 22));

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("findCandidates не возвращает неактивное правило")
    void findCandidatesExcludesInactiveRule() {
        callsignMatchingRepository.save(CallsignMatchingRule.builder()
                .icaoCarrierCode("DLH")
                .flightId("LH0001")
                .specificity(0)
                .active(false)
                .build());

        List<CallsignMatchingRule> candidates =
                callsignMatchingRepository.findCandidates("DLH", LocalDate.of(2026, 6, 22));

        assertThat(candidates).isEmpty();
    }
}
