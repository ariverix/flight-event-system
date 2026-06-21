package ru.protectinfotrans.eca.integration.callsign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;
import ru.protectinfotrans.eca.integration.port.out.CallsignMatchingRepositoryPort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link CallsignMatchingService} — дофильтрация кандидатов (номер рейса/день
 * недели/аэропорты) + выбор max(specificity) + tie-breaker createdAt DESC (P2-4, часть 2).
 *
 * <p>{@link CallsignMatchingRepositoryPort} замокан — здесь проверяется ТОЛЬКО логика выбора
 * среди кандидатов, без реальной БД (период/активность уже проверены в
 * {@code V28CallsignMatchingMigrationIntTest} на стороне db-dev и в отдельном
 * end-to-end интеграционном тесте этого пакета на реальном Postgres).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CallsignMatchingService — дофильтрация кандидатов + выбор по specificity")
class CallsignMatchingServiceTest {

    @Mock
    private CallsignMatchingRepositoryPort repository;

    private CallsignMatchingService service;

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 22); // ISO Пн
    private static final LocalDate SATURDAY = LocalDate.of(2026, 6, 27); // ISO Сб

    @BeforeEach
    void setUp() {
        service = new CallsignMatchingService(new CallsignParser(), repository);
    }

    @Nested
    @DisplayName("Позывной не распознан -> не идём в репозиторий")
    class UnrecognizedCallsign {

        @Test
        @DisplayName("не похоже на позывной -> Optional.empty(), findCandidates не вызывается")
        void returnsEmptyWithoutQueryingRepository() {
            Optional<String> result = service.resolveFlightId("VP-BQR", MONDAY, null, null);

            assertThat(result).isEmpty();
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("Specificity-приоритет: специфичное правило выигрывает у общего")
    class SpecificityPriority {

        @Test
        @DisplayName("общее правило (specificity=0) + специфичное по номеру/дню/аэропортам (specificity=10) -> выигрывает специфичное")
        void specificRuleWinsOverGeneralRule() {
            CallsignMatchingRule generalRule = rule(1L, "AFL", null, "SU1234", null, null, null, 0);
            CallsignMatchingRule specificRule = rule(2L, "AFL", "1234", "SU1234", "1111100", "UUEE", "ULLI", 10);

            when(repository.findCandidates("AFL", MONDAY)).thenReturn(List.of(generalRule, specificRule));

            Optional<String> result = service.resolveFlightId("AFL1234", MONDAY, "UUEE", "ULLI");

            assertThat(result).contains("SU1234");
        }

        @Test
        @DisplayName("специфичное правило не матчится (другой аэропорт) -> остаётся общее")
        void fallsBackToGeneralRuleWhenSpecificDoesNotMatchAirport() {
            CallsignMatchingRule generalRule = rule(1L, "AFL", null, "SU1234", null, null, null, 0);
            CallsignMatchingRule specificRule = rule(2L, "AFL", "1234", "SU9999", "1111100", "UUEE", "ULLI", 10);

            when(repository.findCandidates("AFL", MONDAY)).thenReturn(List.of(generalRule, specificRule));

            // другой аэропорт вылета -> специфичное правило не матчится по аэропорту
            Optional<String> result = service.resolveFlightId("AFL1234", MONDAY, "UUWW", "ULLI");

            assertThat(result).contains("SU1234");
        }

        @Test
        @DisplayName("специфичное правило не матчится (не тот день недели) -> остаётся общее")
        void fallsBackToGeneralRuleWhenSpecificDoesNotMatchDayOfWeek() {
            CallsignMatchingRule generalRule = rule(1L, "AFL", null, "SU1234", null, null, null, 0);
            CallsignMatchingRule specificRule = rule(2L, "AFL", "1234", "SU9999", "1111100", "UUEE", "ULLI", 10);

            when(repository.findCandidates("AFL", SATURDAY)).thenReturn(List.of(generalRule, specificRule));

            // будни-маска 1111100 не включает субботу (позиция 6 = '0')
            Optional<String> result = service.resolveFlightId("AFL1234", SATURDAY, "UUEE", "ULLI");

            assertThat(result).contains("SU1234");
        }

        @Test
        @DisplayName("ни общее, ни специфичное правило не подходят по номеру рейса -> нет совпадения")
        void noMatchWhenFlightNumberDiffersAndNoGeneralRule() {
            CallsignMatchingRule specificRule = rule(1L, "AFL", "1234", "SU1234", null, null, null, 10);

            when(repository.findCandidates("AFL", MONDAY)).thenReturn(List.of(specificRule));

            Optional<String> result = service.resolveFlightId("AFL9999", MONDAY, null, null);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Tie-breaker: равная specificity -> выигрывает более новое правило (createdAt DESC)")
    class TieBreaker {

        @Test
        @DisplayName("два правила с одинаковой specificity -> выигрывает то, что создано позже")
        void newerRuleWinsOnEqualSpecificity() {
            CallsignMatchingRule olderRule = rule(1L, "AFL", "1234", "OLD_FI", null, null, null, 5);
            olderRule.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
            CallsignMatchingRule newerRule = rule(2L, "AFL", "1234", "NEW_FI", null, null, null, 5);
            newerRule.setCreatedAt(LocalDateTime.of(2026, 6, 1, 0, 0));

            when(repository.findCandidates("AFL", MONDAY)).thenReturn(List.of(olderRule, newerRule));

            Optional<String> result = service.resolveFlightId("AFL1234", MONDAY, null, null);

            assertThat(result).contains("NEW_FI");
        }
    }

    @Nested
    @DisplayName("Период/день/аэропорты учитываются — нет совпадения -> нет FI (не выдумываем)")
    class NoMatch {

        @Test
        @DisplayName("findCandidates вернул пустой список (период/активность отфильтрованы в БД) -> Optional.empty()")
        void emptyCandidatesListResultsInEmptyOptional() {
            when(repository.findCandidates(any(), any())).thenReturn(List.of());

            Optional<String> result = service.resolveFlightId("AFL1234", MONDAY, null, null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("кандидат требует конкретный аэропорт, который неизвестен (null) -> не матчится")
        void specificAirportRuleDoesNotMatchWhenActualAirportUnknown() {
            CallsignMatchingRule specificRule = rule(1L, "AFL", "1234", "SU1234", null, "UUEE", "ULLI", 10);

            when(repository.findCandidates("AFL", MONDAY)).thenReturn(List.of(specificRule));

            Optional<String> result = service.resolveFlightId("AFL1234", MONDAY, null, null);

            assertThat(result).isEmpty();
        }
    }

    private CallsignMatchingRule rule(Long id, String carrier, String flightNumber, String flightId,
                                       String daysOfWeek, String departureAirport, String arrivalAirport,
                                       int specificity) {
        return CallsignMatchingRule.builder()
                .id(id)
                .icaoCarrierCode(carrier)
                .flightNumber(flightNumber)
                .flightId(flightId)
                .daysOfWeek(daysOfWeek)
                .departureAirport(departureAirport)
                .arrivalAirport(arrivalAirport)
                .specificity(specificity)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
