package ru.protectinfotrans.eca.execution.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.StepExecution;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.execution.dto.ExecutionInstanceResponse;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.PageResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для ExecutionQueryService.
 * Покрывает листинг с фильтрами, получение по id и маппинг в DTO (включая историю шагов).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionQueryService")
class ExecutionQueryServiceTest {

    @Mock
    private ExecutionRepositoryPort executionRepository;

    @Mock
    private SequenceQueryPort sequenceQuery;

    @InjectMocks
    private ExecutionQueryService service;

    private ExecutionInstance instance;

    @BeforeEach
    void setUp() {
        instance = ExecutionInstance.builder()
                .id(1L)
                .sequenceId(100L)
                .aircraftId("VP-BAB")
                .flightNumber("SU1234")
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .contextJson("{}")
                .startedAt(LocalDateTime.now())
                .stepHistory(new ArrayList<>())
                .build();
    }

    @Nested
    @DisplayName("Список выполнений")
    class ListExecutions {

        @Test
        @DisplayName("должен вернуть страницу с маппингом в DTO")
        void shouldReturnMappedPage() {
            Page<ExecutionInstance> page = new PageImpl<>(List.of(instance));
            when(executionRepository.findByFilters(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(page);
            when(sequenceQuery.findById(100L)).thenReturn(Optional.of(
                    Sequence.builder().id(100L).name("Test Sequence")
                            .status(SequenceStatus.ACTIVE).steps(new ArrayList<>()).build()));

            PageResponse<ExecutionInstanceResponse> response =
                    service.listExecutions(0, 20, ExecutionStatus.RUNNING, "VP-BAB", 100L);

            assertThat(response.content()).hasSize(1);
            assertThat(response.content().get(0).sequenceName()).isEqualTo("Test Sequence");
            assertThat(response.content().get(0).aircraftId()).isEqualTo("VP-BAB");
            assertThat(response.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("должен использовать '—' если последовательность не найдена")
        void shouldUseDashWhenSequenceNotFound() {
            Page<ExecutionInstance> page = new PageImpl<>(List.of(instance));
            when(executionRepository.findByFilters(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(page);
            when(sequenceQuery.findById(100L)).thenReturn(Optional.empty());

            PageResponse<ExecutionInstanceResponse> response =
                    service.listExecutions(0, 20, null, null, null);

            assertThat(response.content().get(0).sequenceName()).isEqualTo("—");
        }

        @Test
        @DisplayName("должен вернуть пустую страницу когда нет выполнений")
        void shouldReturnEmptyPage() {
            Page<ExecutionInstance> page = new PageImpl<>(List.of());
            when(executionRepository.findByFilters(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(page);

            PageResponse<ExecutionInstanceResponse> response =
                    service.listExecutions(0, 20, null, null, null);

            assertThat(response.content()).isEmpty();
            assertThat(response.totalElements()).isZero();
        }

        @Test
        @DisplayName("должен включить историю шагов в ответ")
        void shouldIncludeStepHistory() {
            StepExecution step = StepExecution.builder()
                    .id(1L)
                    .executionInstance(instance)
                    .stepIndex(1)
                    .stepType(StepType.ACTION)
                    .result(StepResult.SUCCESS)
                    .transitionAction(TransitionAction.CONTINUE)
                    .executedAt(LocalDateTime.now())
                    .build();
            instance.getStepHistory().add(step);

            Page<ExecutionInstance> page = new PageImpl<>(List.of(instance));
            when(executionRepository.findByFilters(any(), any(), any(), any(Pageable.class)))
                    .thenReturn(page);
            when(sequenceQuery.findById(100L)).thenReturn(Optional.empty());

            PageResponse<ExecutionInstanceResponse> response =
                    service.listExecutions(0, 20, null, null, null);

            assertThat(response.content().get(0).stepExecutions()).hasSize(1);
            assertThat(response.content().get(0).stepExecutions().get(0).result())
                    .isEqualTo(StepResult.SUCCESS);
        }
    }

    @Nested
    @DisplayName("Получение по id")
    class GetExecution {

        @Test
        @DisplayName("должен вернуть выполнение по id")
        void shouldReturnExecutionById() {
            when(executionRepository.findById(1L)).thenReturn(Optional.of(instance));
            when(sequenceQuery.findById(100L)).thenReturn(Optional.empty());

            ExecutionInstanceResponse response = service.getExecution(1L);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.aircraftId()).isEqualTo("VP-BAB");
        }

        @Test
        @DisplayName("должен бросить исключение если выполнение не найдено")
        void shouldThrowWhenNotFound() {
            when(executionRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getExecution(999L))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }
}
