package ru.protectinfotrans.eca.execution.adapter.in.ws;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.execution.adapter.out.persistence.TrackingEventLogJpaRepository;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.domain.TrackingEventType;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.event.StepTransitionEvent;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.execution.domain.StepResult;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты {@link WsEventBroadcaster} (P7-4).
 *
 * Проверяем:
 *  - onExecutionStarted: broadcast instance-status + event-log (если есть TrackingLog)
 *  - onExecutionCompleted: lookup instance → broadcast instance-status + event-log
 *  - onStepTransition: lookup instance → broadcast instance-status + event-log
 *  - broadcastLatestTrackingEvent: нет записи → event-log не рассылается
 */
@ExtendWith(MockitoExtension.class)
class WsEventBroadcasterTest {

    @Mock EcaWsBroadcaster              broadcaster;
    @Mock ExecutionRepositoryPort       executionRepo;
    @Mock TrackingEventLogJpaRepository trackingRepo;

    @InjectMocks
    WsEventBroadcaster wsEventBroadcaster;

    // ── Хелперы ───────────────────────────────────────────────────────────────

    private ExecutionInstance buildInstance(Long id, Long seqId, String aircraftId, ExecutionStatus status, Integer step) {
        return ExecutionInstance.builder()
                .id(id)
                .sequenceId(seqId)
                .aircraftId(aircraftId)
                .flightNumber("SU1234")
                .status(status)
                .currentStepIndex(step)
                .build();
    }

    private TrackingEventLog buildTrackingEntry(Long id, Long instanceId, TrackingEventType type) {
        return TrackingEventLog.builder()
                .id(id)
                .instanceId(instanceId)
                .sequenceId(10L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .eventType(type)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── onExecutionStarted ────────────────────────────────────────────────────

    @Test
    void onExecutionStarted_broadcastsInstanceStatusAndEventLog() {
        var event = new ExecutionStartedEvent(42L, 10L, "VP-BQR", "SU1234");
        TrackingEventLog entry = buildTrackingEntry(1L, 42L, TrackingEventType.SEQUENCE_STARTED);
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(42L)).thenReturn(Optional.of(entry));

        wsEventBroadcaster.onExecutionStarted(event);

        // instance-status с RUNNING
        ArgumentCaptor<Map> statusCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).broadcast(eq("instance-status"), statusCaptor.capture());
        assertThat(statusCaptor.getValue()).containsEntry("instanceId", 42L)
                .containsEntry("status", "RUNNING")
                .containsEntry("currentStepIndex", 1);

        // event-log
        ArgumentCaptor<Map> logCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).broadcast(eq("event-log"), logCaptor.capture());
        assertThat(logCaptor.getValue()).containsEntry("instanceId", 42L)
                .containsEntry("eventType", "SEQUENCE_STARTED");
    }

    @Test
    void onExecutionStarted_noTrackingEntry_onlyInstanceStatusBroadcast() {
        var event = new ExecutionStartedEvent(99L, 10L, "VP-BQR", "SU1234");
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(99L)).thenReturn(Optional.empty());

        wsEventBroadcaster.onExecutionStarted(event);

        verify(broadcaster, times(1)).broadcast(eq("instance-status"), any());
        verify(broadcaster, never()).broadcast(eq("event-log"), any());
    }

    // ── onExecutionCompleted ──────────────────────────────────────────────────

    @Test
    void onExecutionCompleted_lookupInstanceAndBroadcast() {
        var event = new ExecutionCompletedEvent(55L, ExecutionStatus.COMPLETED);
        ExecutionInstance instance = buildInstance(55L, 10L, "VP-BQR", ExecutionStatus.COMPLETED, null);
        instance.setCompletedAt(LocalDateTime.now());
        when(executionRepo.findById(55L)).thenReturn(Optional.of(instance));

        TrackingEventLog entry = buildTrackingEntry(2L, 55L, TrackingEventType.SEQUENCE_STOPPED);
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(55L)).thenReturn(Optional.of(entry));

        wsEventBroadcaster.onExecutionCompleted(event);

        ArgumentCaptor<Map> statusCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).broadcast(eq("instance-status"), statusCaptor.capture());
        assertThat(statusCaptor.getValue())
                .containsEntry("status", "COMPLETED")
                .containsEntry("instanceId", 55L);

        verify(broadcaster).broadcast(eq("event-log"), any());
    }

    @Test
    void onExecutionCompleted_instanceNotFound_noInstanceStatusBroadcast() {
        var event = new ExecutionCompletedEvent(77L, ExecutionStatus.ABORTED);
        when(executionRepo.findById(77L)).thenReturn(Optional.empty());
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(77L)).thenReturn(Optional.empty());

        wsEventBroadcaster.onExecutionCompleted(event);

        verify(broadcaster, never()).broadcast(eq("instance-status"), any());
    }

    // ── onStepTransition ──────────────────────────────────────────────────────

    @Test
    void onStepTransition_broadcastsUpdatedStep() {
        var event = new StepTransitionEvent(33L, 1, 2, StepResult.SUCCESS, TransitionAction.CONTINUE);
        ExecutionInstance instance = buildInstance(33L, 10L, "VP-BQR", ExecutionStatus.RUNNING, 2);
        when(executionRepo.findById(33L)).thenReturn(Optional.of(instance));

        TrackingEventLog entry = buildTrackingEntry(3L, 33L, TrackingEventType.STEP_COMPLETED);
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(33L)).thenReturn(Optional.of(entry));

        wsEventBroadcaster.onStepTransition(event);

        ArgumentCaptor<Map> statusCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster).broadcast(eq("instance-status"), statusCaptor.capture());
        assertThat(statusCaptor.getValue())
                .containsEntry("instanceId", 33L)
                .containsEntry("currentStepIndex", 2)
                .containsEntry("status", "RUNNING");

        verify(broadcaster).broadcast(eq("event-log"), any());
    }

    @Test
    void onStepTransition_instanceNotFound_noStatusBroadcast() {
        var event = new StepTransitionEvent(44L, 1, 2, StepResult.FAILURE, TransitionAction.GOTO);
        when(executionRepo.findById(44L)).thenReturn(Optional.empty());
        when(trackingRepo.findTopByInstanceIdOrderByIdDesc(44L)).thenReturn(Optional.empty());

        wsEventBroadcaster.onStepTransition(event);

        verify(broadcaster, never()).broadcast(eq("instance-status"), any());
    }
}
