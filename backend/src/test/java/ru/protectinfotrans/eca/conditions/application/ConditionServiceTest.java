package ru.protectinfotrans.eca.conditions.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.conditions.domain.ConditionAlreadyRaisedException;
import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.conditions.port.out.ConditionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты движка условий/алертов (P3-3) — raise/close custom condition, независимость
 * условия и уровня алерта, "нельзя поднять дважды одним именем", и авто-закрытие активных
 * условий рейса на терминальных стадиях полёта (паритет с {@code CustomFieldExtractionServiceTest}
 * для P3-2 — структурно тот же паттерн).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConditionService")
class ConditionServiceTest {

    @Mock
    private ConditionRepositoryPort repository;

    private ConditionService service;

    @BeforeEach
    void setUp() {
        service = new ConditionService(repository, new SimpleMeterRegistry());
    }

    private RaisedCondition activeCondition(String aircraftId, String flightNumber, String name, AlertLevel level) {
        return RaisedCondition.builder()
                .id(1L)
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .conditionName(name)
                .alertLevel(level)
                .raisedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("raiseCondition")
    class RaiseConditionTests {

        @Test
        @DisplayName("должен поднять условие с заданным уровнем алерта и сохранить через репозиторий")
        void shouldRaiseConditionWithGivenAlertLevel() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "NO_POSITION")).thenReturn(Optional.empty());

            service.raiseCondition("VP-BQR", "SU1234", "NO_POSITION", AlertLevel.HIGH);

            ArgumentCaptor<RaisedCondition> captor = ArgumentCaptor.forClass(RaisedCondition.class);
            verify(repository).save(captor.capture());
            RaisedCondition saved = captor.getValue();
            assertThat(saved.getAircraftId()).isEqualTo("VP-BQR");
            assertThat(saved.getFlightNumber()).isEqualTo("SU1234");
            assertThat(saved.getConditionName()).isEqualTo("NO_POSITION");
            assertThat(saved.getAlertLevel()).isEqualTo(AlertLevel.HIGH);
            assertThat(saved.getClosedAt()).isNull();
        }

        @Test
        @DisplayName("условие и уровень алерта независимы: можно поднять условие с уровнем NO "
                + "(алертинг отсутствует, но условие физически активно)")
        void shouldAllowRaisingWithNoAlertLevel() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    anyString(), anyString(), anyString())).thenReturn(Optional.empty());

            service.raiseCondition("VP-BQR", "SU1234", "SILENT_TRACKING", AlertLevel.NO);

            ArgumentCaptor<RaisedCondition> captor = ArgumentCaptor.forClass(RaisedCondition.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getAlertLevel()).isEqualTo(AlertLevel.NO);
        }

        @Test
        @DisplayName("повторный raise уже активного условия тем же именем -> ConditionAlreadyRaisedException, "
                + "save() не вызывается (паритет SITA \"нельзя поднять дважды одним именем\")")
        void shouldRejectDuplicateRaiseOfActiveCondition() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "DELAYED"))
                    .thenReturn(Optional.of(activeCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.LOW)));

            assertThatThrownBy(() -> service.raiseCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.HIGH))
                    .isInstanceOf(ConditionAlreadyRaisedException.class)
                    .hasMessageContaining("DELAYED")
                    .hasMessageContaining("VP-BQR")
                    .hasMessageContaining("SU1234");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("после close условия с тем же именем повторный raise разрешён (создаёт НОВУЮ запись)")
        void shouldAllowReRaiseAfterClose() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "DELAYED")).thenReturn(Optional.empty());

            service.raiseCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.MEDIUM);

            verify(repository, times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("closeCondition")
    class CloseConditionTests {

        @Test
        @DisplayName("должен закрыть активное условие (проставить closedAt) через save")
        void shouldCloseActiveCondition() {
            RaisedCondition active = activeCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.HIGH);
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "DELAYED")).thenReturn(Optional.of(active));

            service.closeCondition("VP-BQR", "SU1234", "DELAYED");

            ArgumentCaptor<RaisedCondition> captor = ArgumentCaptor.forClass(RaisedCondition.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("закрытие уже закрытого/никогда не поднятого условия -> идемпотентный no-op, "
                + "save() не вызывается, исключение не бросается")
        void shouldNoOpWhenClosingInactiveCondition() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "UNKNOWN")).thenReturn(Optional.empty());

            service.closeCondition("VP-BQR", "SU1234", "UNKNOWN");

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("isConditionActive / getActiveConditions")
    class QueryTests {

        @Test
        @DisplayName("isConditionActive -> true, если есть активная (не закрытая) строка")
        void shouldReturnTrueWhenConditionActive() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "DELAYED"))
                    .thenReturn(Optional.of(activeCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.HIGH)));

            assertThat(service.isConditionActive("VP-BQR", "SU1234", "DELAYED")).isTrue();
        }

        @Test
        @DisplayName("isConditionActive -> false, если условие не поднято")
        void shouldReturnFalseWhenConditionNotRaised() {
            when(repository.findActiveByAircraftIdAndFlightNumberAndConditionName(
                    "VP-BQR", "SU1234", "UNKNOWN")).thenReturn(Optional.empty());

            assertThat(service.isConditionActive("VP-BQR", "SU1234", "UNKNOWN")).isFalse();
        }

        @Test
        @DisplayName("getActiveConditions -> карта conditionName -> alertLevel всех активных условий рейса")
        void shouldReturnMapOfActiveConditionsWithAlertLevels() {
            when(repository.findActiveByAircraftIdAndFlightNumber("VP-BQR", "SU1234"))
                    .thenReturn(List.of(
                            activeCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.HIGH),
                            activeCondition("VP-BQR", "SU1234", "NO_POSITION", AlertLevel.MEDIUM)));

            Map<String, AlertLevel> active = service.getActiveConditions("VP-BQR", "SU1234");

            assertThat(active).containsEntry("DELAYED", AlertLevel.HIGH)
                    .containsEntry("NO_POSITION", AlertLevel.MEDIUM);
        }

        @Test
        @DisplayName("getActiveConditions -> пустая карта, если ни одного условия не поднято")
        void shouldReturnEmptyMapWhenNoActiveConditions() {
            when(repository.findActiveByAircraftIdAndFlightNumber("VP-BQR", "SU1234")).thenReturn(List.of());

            assertThat(service.getActiveConditions("VP-BQR", "SU1234")).isEmpty();
        }

        @Test
        @DisplayName("getActiveConditions -> пустая карта (без обращения к репозиторию), если aircraftId/flightNumber не заданы")
        void shouldReturnEmptyMapWithoutRepositoryCallWhenKeysMissing() {
            assertThat(service.getActiveConditions(null, "SU1234")).isEmpty();
            assertThat(service.getActiveConditions("VP-BQR", null)).isEmpty();

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("listAllActive -> делегирует в репозиторий (операторский обзор по всем бортам/рейсам)")
        void shouldDelegateListAllActiveToRepository() {
            List<RaisedCondition> all = List.of(activeCondition("VP-BQR", "SU1234", "DELAYED", AlertLevel.HIGH));
            when(repository.findAllActive()).thenReturn(all);

            assertThat(service.listAllActive()).isEqualTo(all);
        }
    }

    @Nested
    @DisplayName("onFlightStageChanged — авто-закрытие активных условий рейса")
    class AutoCloseTests {

        @Test
        @DisplayName("стадия IN -> закрывает все активные условия рейса через repository.closeAllActiveForFlight")
        void shouldAutoCloseOnInStage() {
            when(repository.closeAllActiveForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class)))
                    .thenReturn(2);

            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.IN);

            verify(repository).closeAllActiveForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("стадия SUMMARY -> также закрывает все активные условия рейса")
        void shouldAutoCloseOnSummaryStage() {
            when(repository.closeAllActiveForFlight(anyString(), anyString(), any(LocalDateTime.class)))
                    .thenReturn(1);

            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.SUMMARY);

            verify(repository).closeAllActiveForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("нетерминальные стадии (INIT/OUT/OFF/ON) НЕ закрывают активные условия")
        void shouldNotAutoCloseOnNonTerminalStages() {
            for (FlightStage stage : List.of(FlightStage.INIT, FlightStage.OUT, FlightStage.OFF, FlightStage.ON)) {
                service.onFlightStageChanged("VP-BQR", "SU1234", stage);
            }

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("flightNumber отсутствует -> авто-закрытие пропускается (защита от закрытия условий чужого рейса)")
        void shouldSkipAutoCloseWhenFlightNumberMissing() {
            service.onFlightStageChanged("VP-BQR", null, FlightStage.IN);

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("aircraftId отсутствует -> авто-закрытие пропускается")
        void shouldSkipAutoCloseWhenAircraftIdMissing() {
            service.onFlightStageChanged(null, "SU1234", FlightStage.IN);

            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("повторный вызов на рейсе без активных условий -> идемпотентно (0 закрытых), не ошибка")
        void shouldBeIdempotentWhenNoActiveConditionsToClose() {
            when(repository.closeAllActiveForFlight(anyString(), anyString(), any(LocalDateTime.class)))
                    .thenReturn(0);

            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.IN);
            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.SUMMARY);

            verify(repository, times(2))
                    .closeAllActiveForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class));
        }
    }
}
