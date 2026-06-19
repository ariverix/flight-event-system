package ru.protectinfotrans.eca.execution.adapter.in.rest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.execution.port.in.ExecutionManagementUseCase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для ExecutionController.
 * Проверяет делегирование к ExecutionManagementUseCase и формирование ResponseEntity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionController")
class ExecutionControllerTest {

    @Mock
    private ExecutionManagementUseCase executionManagement;

    @InjectMocks
    private ExecutionController controller;

    @Nested
    @DisplayName("listExecutions")
    class ListExecutions {

        @Test
        @DisplayName("должен делегировать получение списка с фильтрами и возвращать 200 OK")
        void shouldListExecutionsWithFilters() {
            var instance = new ExecutionInstanceResponse(
                    1L, 2L, "Seq", "RA-1234", "SU100", ExecutionStatus.RUNNING,
                    0, "{}", null, null, null, null, null, List.of());
            var page = new PageResponse<>(List.of(instance), 1, 1, 0, 20);
            when(executionManagement.listExecutions(0, 20, ExecutionStatus.RUNNING, "RA-1234", 2L))
                    .thenReturn(page);

            ResponseEntity<PageResponse<ExecutionInstanceResponse>> response =
                    controller.listExecutions(0, 20, ExecutionStatus.RUNNING, "RA-1234", 2L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(page);
            verify(executionManagement).listExecutions(0, 20, ExecutionStatus.RUNNING, "RA-1234", 2L);
        }

        @Test
        @DisplayName("должен делегировать получение списка без фильтров (значения по умолчанию)")
        void shouldListExecutionsWithDefaults() {
            var page = new PageResponse<ExecutionInstanceResponse>(List.of(), 0, 0, 0, 20);
            when(executionManagement.listExecutions(0, 20, null, null, null))
                    .thenReturn(page);

            ResponseEntity<PageResponse<ExecutionInstanceResponse>> response =
                    controller.listExecutions(0, 20, null, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(page);
            assertThat(response.getBody().content()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getExecution")
    class GetExecution {

        @Test
        @DisplayName("должен делегировать получение деталей экземпляра по id и возвращать 200 OK")
        void shouldGetExecutionById() {
            var instance = new ExecutionInstanceResponse(
                    5L, 2L, "Seq", "RA-1234", "SU100", ExecutionStatus.COMPLETED,
                    3, "{}", null, null, null, null, null, List.of());
            when(executionManagement.getExecution(5L)).thenReturn(instance);

            ResponseEntity<ExecutionInstanceResponse> response = controller.getExecution(5L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(instance);
            verify(executionManagement).getExecution(5L);
        }
    }
}
