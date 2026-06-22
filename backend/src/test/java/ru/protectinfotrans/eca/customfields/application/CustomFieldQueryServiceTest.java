package ru.protectinfotrans.eca.customfields.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldValue;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldValueRepositoryPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomFieldQueryService")
class CustomFieldQueryServiceTest {

    @Mock
    private CustomFieldValueRepositoryPort valueRepository;

    private CustomFieldQueryService service;

    @BeforeEach
    void setUp() {
        service = new CustomFieldQueryService(valueRepository);
    }

    private CustomFieldValue value(String fieldName, String value) {
        return CustomFieldValue.builder()
                .fieldName(fieldName)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .value(value)
                .build();
    }

    @Test
    @DisplayName("должен вернуть значения с префиксом 'customField.'")
    void shouldReturnValuesWithPrefix() {
        when(valueRepository.findActiveByAircraftIdAndFlightNumber("VP-BQR", "SU1234"))
                .thenReturn(List.of(value("GATE_NUMBER", "A12"), value("RUNWAY", "25L")));

        Map<String, String> result = service.getActiveValues("VP-BQR", "SU1234");

        assertThat(result)
                .containsEntry("customField.GATE_NUMBER", "A12")
                .containsEntry("customField.RUNWAY", "25L");
    }

    @Test
    @DisplayName("без активных значений -> пустая карта")
    void shouldReturnEmptyMapWhenNoActiveValues() {
        when(valueRepository.findActiveByAircraftIdAndFlightNumber("VP-BQR", "SU1234"))
                .thenReturn(List.of());

        assertThat(service.getActiveValues("VP-BQR", "SU1234")).isEmpty();
    }

    @Test
    @DisplayName("aircraftId=null -> пустая карта, репозиторий не вызывается")
    void shouldReturnEmptyMapWhenAircraftIdNull() {
        assertThat(service.getActiveValues(null, "SU1234")).isEmpty();
        verifyNoInteractions(valueRepository);
    }

    @Test
    @DisplayName("flightNumber=null -> пустая карта, репозиторий не вызывается")
    void shouldReturnEmptyMapWhenFlightNumberNull() {
        assertThat(service.getActiveValues("VP-BQR", null)).isEmpty();
        verifyNoInteractions(valueRepository);
    }
}
