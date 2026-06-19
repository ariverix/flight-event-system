package ru.protectinfotrans.eca.execution.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P1-4: unit-тесты для {@link ExecutionResumeRunner} — компонент старта приложения,
 * восстанавливающий незавершённые экземпляры. Использует реальный {@link SimpleMeterRegistry},
 * а не mock — проверка gauge-метрик имеет смысл только на реальной реализации.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExecutionResumeRunner")
class ExecutionResumeRunnerTest {

    @Mock
    private ExecutionRepositoryPort executionRepository;

    @Mock
    private ExecutionService executionService;

    private SimpleMeterRegistry meterRegistry;
    private ExecutionResumeRunner runner;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        runner = new ExecutionResumeRunner(executionRepository, executionService, meterRegistry);
    }

    private ExecutionInstance instance(Long id, ExecutionStatus status, int stepIndex) {
        return ExecutionInstance.builder()
                .id(id)
                .sequenceId(100L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .status(status)
                .currentStepIndex(stepIndex)
                .stepHistory(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("должен вызвать resumeRunningInstanceAfterRestart для каждого RUNNING-инстанса")
    void shouldResumeEachRunningInstance() {
        ExecutionInstance running1 = instance(1L, ExecutionStatus.RUNNING, 1);
        ExecutionInstance running2 = instance(2L, ExecutionStatus.RUNNING, 3);
        when(executionRepository.findAllActive()).thenReturn(List.of(running1, running2));

        runner.run(null);

        verify(executionService).resumeRunningInstanceAfterRestart(running1);
        verify(executionService).resumeRunningInstanceAfterRestart(running2);
    }

    @Test
    @DisplayName("НЕ должен трогать WAITING-инстансы — их WAIT-окно уже персистентно")
    void shouldNotTouchWaitingInstances() {
        ExecutionInstance waiting = instance(3L, ExecutionStatus.WAITING, 2);
        when(executionRepository.findAllActive()).thenReturn(List.of(waiting));

        runner.run(null);

        verify(executionService, never()).resumeRunningInstanceAfterRestart(any());
    }

    @Test
    @DisplayName("сбой резюма одного инстанса не должен прерывать обработку остальных")
    void shouldContinueAfterOneInstanceFailsToResume() {
        ExecutionInstance failing = instance(4L, ExecutionStatus.RUNNING, 1);
        ExecutionInstance healthy = instance(5L, ExecutionStatus.RUNNING, 1);
        when(executionRepository.findAllActive()).thenReturn(List.of(failing, healthy));
        doThrow(new RuntimeException("boom")).when(executionService).resumeRunningInstanceAfterRestart(failing);

        runner.run(null);

        verify(executionService).resumeRunningInstanceAfterRestart(failing);
        verify(executionService).resumeRunningInstanceAfterRestart(healthy);
    }

    @Test
    @DisplayName("должен публиковать метрики количества восстановленных инстансов по статусу")
    void shouldPublishResumeMetrics() {
        when(executionRepository.findAllActive()).thenReturn(List.of(
                instance(1L, ExecutionStatus.RUNNING, 1),
                instance(2L, ExecutionStatus.RUNNING, 2),
                instance(3L, ExecutionStatus.WAITING, 1)
        ));

        runner.run(null);

        assertThat(meterRegistry.get("eca.execution.resumed.instances").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("eca.execution.resumed.running").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("eca.execution.resumed.waiting").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("должен корректно работать без активных инстансов (пустая БД)")
    void shouldHandleNoActiveInstances() {
        when(executionRepository.findAllActive()).thenReturn(List.of());

        runner.run(null);

        verify(executionService, never()).resumeRunningInstanceAfterRestart(any());
        assertThat(meterRegistry.get("eca.execution.resumed.instances").gauge().value()).isEqualTo(0.0);
    }
}
