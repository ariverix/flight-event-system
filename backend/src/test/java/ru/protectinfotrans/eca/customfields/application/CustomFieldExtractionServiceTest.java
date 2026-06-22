package ru.protectinfotrans.eca.customfields.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldRuleRepositoryPort;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldValueRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты движка извлечения custom fields (P3-2) — извлечение из CONTENT/METADATA, отсутствие
 * совпадений, и закрытие per-flight контекста на терминальных стадиях полёта.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomFieldExtractionService")
class CustomFieldExtractionServiceTest {

    @Mock
    private CustomFieldRuleRepositoryPort ruleRepository;

    @Mock
    private CustomFieldValueRepositoryPort valueRepository;

    private CustomFieldExtractionService service;

    @BeforeEach
    void setUp() {
        service = new CustomFieldExtractionService(ruleRepository, valueRepository);
    }

    private CustomFieldRule contentRule(String name, String pattern) {
        return CustomFieldRule.builder()
                .id(1L)
                .name(name)
                .messageType(MessageType.DOWNLINK)
                .extractionSource(ExtractionSource.CONTENT)
                .pattern(pattern)
                .active(true)
                .build();
    }

    private CustomFieldRule metadataRule(String name, String key) {
        return CustomFieldRule.builder()
                .id(2L)
                .name(name)
                .messageType(MessageType.DOWNLINK)
                .extractionSource(ExtractionSource.METADATA)
                .pattern(key)
                .active(true)
                .build();
    }

    @Nested
    @DisplayName("extract")
    class ExtractTests {

        @Test
        @DisplayName("CONTENT: должен извлечь значение по regex и записать per-flight через upsert")
        void shouldExtractFromContentAndUpsert() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "STATUS"))
                    .thenReturn(List.of(contentRule("GATE_NUMBER", "GATE=(\\w+)")));

            service.extract(10L, MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234",
                    "STATUS GATE=A12 OK", Map.of());

            ArgumentCaptor<CustomFieldValue> captor = ArgumentCaptor.forClass(CustomFieldValue.class);
            verify(valueRepository).upsert(captor.capture());
            CustomFieldValue saved = captor.getValue();
            assertThat(saved.getFieldName()).isEqualTo("GATE_NUMBER");
            assertThat(saved.getAircraftId()).isEqualTo("VP-BQR");
            assertThat(saved.getFlightNumber()).isEqualTo("SU1234");
            assertThat(saved.getValue()).isEqualTo("A12");
            assertThat(saved.getSourceMessageId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("METADATA: должен извлечь значение по ключу metadata и записать через upsert")
        void shouldExtractFromMetadataAndUpsert() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "OOOI"))
                    .thenReturn(List.of(metadataRule("PAX_COUNT", "paxCount")));

            service.extract(11L, MessageType.DOWNLINK, "OOOI", "VP-BQR", "SU1234",
                    "OFF UUEE", Map.of("paxCount", 180));

            ArgumentCaptor<CustomFieldValue> captor = ArgumentCaptor.forClass(CustomFieldValue.class);
            verify(valueRepository).upsert(captor.capture());
            assertThat(captor.getValue().getFieldName()).isEqualTo("PAX_COUNT");
            assertThat(captor.getValue().getValue()).isEqualTo("180");
        }

        @Test
        @DisplayName("regex не совпал -> upsert не вызывается, существующее значение не затирается")
        void shouldNotUpsertWhenContentPatternDoesNotMatch() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "STATUS"))
                    .thenReturn(List.of(contentRule("GATE_NUMBER", "GATE=(\\w+)")));

            service.extract(12L, MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234",
                    "STATUS OK NO GATE INFO", Map.of());

            verify(valueRepository, never()).upsert(any());
        }

        @Test
        @DisplayName("ключ metadata отсутствует -> upsert не вызывается")
        void shouldNotUpsertWhenMetadataKeyAbsent() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "OOOI"))
                    .thenReturn(List.of(metadataRule("PAX_COUNT", "paxCount")));

            service.extract(13L, MessageType.DOWNLINK, "OOOI", "VP-BQR", "SU1234",
                    "OFF UUEE", Map.of("otherKey", "value"));

            verify(valueRepository, never()).upsert(any());
        }

        @Test
        @DisplayName("без применимых правил -> репозиторий значений не трогается")
        void shouldDoNothingWhenNoApplicableRules() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "STATUS"))
                    .thenReturn(List.of());

            service.extract(14L, MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "STATUS OK", Map.of());

            verifyNoInteractions(valueRepository);
        }

        @Test
        @DisplayName("без aircraftId -> экстракция неприменима, репозитории не трогаются")
        void shouldSkipWhenAircraftIdMissing() {
            service.extract(15L, MessageType.DOWNLINK, "STATUS", null, "SU1234", "STATUS GATE=A12", Map.of());

            verifyNoInteractions(ruleRepository);
            verifyNoInteractions(valueRepository);
        }

        @Test
        @DisplayName("несколько применимых правил -> каждое обрабатывается отдельно, каждый upsert свой")
        void shouldProcessMultipleApplicableRulesIndependently() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "STATUS"))
                    .thenReturn(List.of(
                            contentRule("GATE_NUMBER", "GATE=(\\w+)"),
                            contentRule("RUNWAY", "RWY=(\\w+)")));

            service.extract(16L, MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234",
                    "STATUS GATE=A12 RWY=25L", Map.of());

            verify(valueRepository, times(2)).upsert(any(CustomFieldValue.class));
        }

        @Test
        @DisplayName("кривой regex на сохранённом правиле (defense in depth) -> не падает, upsert не вызывается")
        void shouldNotFailOnBrokenStoredRegex() {
            when(ruleRepository.findActiveApplicableRules(MessageType.DOWNLINK, "STATUS"))
                    .thenReturn(List.of(contentRule("BROKEN", "GATE=([")));

            service.extract(17L, MessageType.DOWNLINK, "STATUS", "VP-BQR", "SU1234", "STATUS GATE=A12", Map.of());

            verify(valueRepository, never()).upsert(any());
        }
    }

    @Nested
    @DisplayName("onFlightStageChanged — закрытие per-flight контекста")
    class FlightContextClosureTests {

        @Test
        @DisplayName("стадия IN -> закрывает все открытые значения рейса")
        void shouldCloseContextOnInStage() {
            when(valueRepository.closeAllOpenForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class)))
                    .thenReturn(3);

            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.IN);

            verify(valueRepository).closeAllOpenForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("стадия SUMMARY -> закрывает все открытые значения рейса")
        void shouldCloseContextOnSummaryStage() {
            when(valueRepository.closeAllOpenForFlight(anyString(), anyString(), any(LocalDateTime.class)))
                    .thenReturn(1);

            service.onFlightStageChanged("VP-BQR", "SU1234", FlightStage.SUMMARY);

            verify(valueRepository).closeAllOpenForFlight(eq("VP-BQR"), eq("SU1234"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("нетерминальные стадии (INIT/OUT/OFF/ON) НЕ закрывают контекст")
        void shouldNotCloseContextOnNonTerminalStages() {
            for (FlightStage stage : List.of(FlightStage.INIT, FlightStage.OUT, FlightStage.OFF, FlightStage.ON)) {
                service.onFlightStageChanged("VP-BQR", "SU1234", stage);
            }

            verifyNoInteractions(valueRepository);
        }

        @Test
        @DisplayName("flightNumber отсутствует -> закрытие пропускается (защита от закрытия чужого рейса борта)")
        void shouldSkipClosureWhenFlightNumberMissing() {
            service.onFlightStageChanged("VP-BQR", null, FlightStage.IN);

            verifyNoInteractions(valueRepository);
        }

        @Test
        @DisplayName("aircraftId отсутствует -> закрытие пропускается")
        void shouldSkipClosureWhenAircraftIdMissing() {
            service.onFlightStageChanged(null, "SU1234", FlightStage.IN);

            verifyNoInteractions(valueRepository);
        }
    }
}
