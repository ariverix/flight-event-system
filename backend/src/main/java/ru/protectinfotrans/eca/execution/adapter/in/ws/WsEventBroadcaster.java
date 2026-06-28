package ru.protectinfotrans.eca.execution.adapter.in.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.adapter.out.persistence.TrackingEventLogJpaRepository;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.event.ExecutionCompletedEvent;
import ru.protectinfotrans.eca.execution.event.ExecutionStartedEvent;
import ru.protectinfotrans.eca.execution.event.StepTransitionEvent;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Транслятор событий движка в WebSocket-рассылку (P7-4).
 *
 * <p>Слушает Modulith-события ({@code @ApplicationModuleListener} = {@code @TransactionalEventListener}
 * after-commit) и отправляет соответствующие WS-сообщения клиентам на каналы:
 * <ul>
 *   <li>{@code instance-status} — текущий статус инстанса (при старте, смене шага, завершении)</li>
 *   <li>{@code event-log}       — последняя запись Tracking Event Log для инстанса</li>
 * </ul>
 *
 * <p>Запросы к БД выполняются ПОСЛЕ коммита транзакции (after-commit delivery Modulith),
 * поэтому все данные уже видны — нет риска dirty-read.
 *
 * <p>Нахождение в {@code execution/adapter/in/ws/} соответствует гексагональной архитектуре:
 * WS — это входящий адаптер (клиенты получают push-уведомления как результат бизнес-событий).
 * Прямой инжект {@code ExecutionJpaRepository} и {@code TrackingEventLogJpaRepository}
 * не нарушает модульных границ — все классы в одном модуле {@code execution}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WsEventBroadcaster {

    private static final String CH_INSTANCE_STATUS = "instance-status";
    private static final String CH_EVENT_LOG       = "event-log";
    private static final DateTimeFormatter ISO_FMT  = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final EcaWsBroadcaster              broadcaster;
    private final ExecutionRepositoryPort       executionRepo;
    private final TrackingEventLogJpaRepository trackingRepo;

    // ── ExecutionStartedEvent ──────────────────────────────────────────────────

    @ApplicationModuleListener
    public void onExecutionStarted(ExecutionStartedEvent event) {
        log.debug("WS broadcast: ExecutionStarted instanceId={}", event.id());

        Map<String, Object> statusPayload = buildStatusPayload(
                event.id(),
                event.sequenceId(),
                event.aircraftId(),
                event.flightNumber(),
                "RUNNING",
                1,
                LocalDateTime.now()
        );
        broadcaster.broadcast(CH_INSTANCE_STATUS, statusPayload);

        broadcastLatestTrackingEvent(event.id());
    }

    // ── ExecutionCompletedEvent ────────────────────────────────────────────────

    @ApplicationModuleListener
    public void onExecutionCompleted(ExecutionCompletedEvent event) {
        log.debug("WS broadcast: ExecutionCompleted instanceId={} status={}",
                event.executionId(), event.finalStatus());

        executionRepo.findById(event.executionId()).ifPresent(instance -> {
            Map<String, Object> statusPayload = buildStatusPayload(
                    instance.getId(),
                    instance.getSequenceId(),
                    instance.getAircraftId(),
                    instance.getFlightNumber(),
                    event.finalStatus().name(),
                    instance.getCurrentStepIndex(),
                    instance.getCompletedAt() != null ? instance.getCompletedAt() : LocalDateTime.now()
            );
            broadcaster.broadcast(CH_INSTANCE_STATUS, statusPayload);
        });

        broadcastLatestTrackingEvent(event.executionId());
    }

    // ── StepTransitionEvent ────────────────────────────────────────────────────

    @ApplicationModuleListener
    public void onStepTransition(StepTransitionEvent event) {
        log.debug("WS broadcast: StepTransition instanceId={} step {} → {}",
                event.executionId(), event.fromStep(), event.toStep());

        executionRepo.findById(event.executionId()).ifPresent(instance -> {
            Map<String, Object> statusPayload = buildStatusPayload(
                    instance.getId(),
                    instance.getSequenceId(),
                    instance.getAircraftId(),
                    instance.getFlightNumber(),
                    instance.getStatus().name(),
                    instance.getCurrentStepIndex(),
                    LocalDateTime.now()
            );
            broadcaster.broadcast(CH_INSTANCE_STATUS, statusPayload);
        });

        broadcastLatestTrackingEvent(event.executionId());
    }

    // ── Приватные методы ────────────────────────────────────────────────────────

    private Map<String, Object> buildStatusPayload(
            Long instanceId, Long sequenceId, String aircraftId, String flightNumber,
            String status, Integer currentStepIndex, LocalDateTime updatedAt) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("instanceId",       instanceId);
        payload.put("sequenceId",       sequenceId);
        payload.put("aircraftId",       aircraftId);
        payload.put("flightNumber",     flightNumber);
        payload.put("status",           status);
        payload.put("currentStepIndex", currentStepIndex);
        payload.put("updatedAt",        updatedAt != null ? updatedAt.format(ISO_FMT) : null);
        return payload;
    }

    private void broadcastLatestTrackingEvent(Long instanceId) {
        trackingRepo.findTopByInstanceIdOrderByIdDesc(instanceId).ifPresent(entry -> {
            Map<String, Object> logPayload = buildEventLogPayload(entry);
            broadcaster.broadcast(CH_EVENT_LOG, logPayload);
        });
    }

    private Map<String, Object> buildEventLogPayload(TrackingEventLog entry) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id",            entry.getId());
        payload.put("eventType",     entry.getEventType() != null ? entry.getEventType().name() : null);
        payload.put("instanceId",    entry.getInstanceId());
        payload.put("sequenceId",    entry.getSequenceId());
        payload.put("aircraftId",    entry.getAircraftId());
        payload.put("flightNumber",  entry.getFlightNumber());
        payload.put("stepIndex",     entry.getStepIndex());
        payload.put("detailsJson",   entry.getDetailsJson());
        payload.put("correlationId", entry.getCorrelationId());
        payload.put("createdAt",     entry.getCreatedAt() != null
                ? entry.getCreatedAt().format(ISO_FMT) : null);
        return payload;
    }
}
