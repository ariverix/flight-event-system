package ru.protectinfotrans.eca.eventprocessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.execution.application.ExecutionService;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceResponse;
import ru.protectinfotrans.eca.sequence.dto.StepCreateRequest;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионный тест реального пути приёма POSITION_REPORT через
 * {@link MessageInputPort#receiveMessage}, идентичного тому, что шлёт фронтенд
 * (DemoPage / MessageSimulator): metadata вида {"latitude":..,"longitude":..}.
 * <p>
 * Реальный баг (найден ревью P1-1): POSITION_REPORTED-критерий требует
 * {@code position_source IS NOT NULL}, а фронтенд исторически не передавал
 * {@code positionSource} в metadata — отчёт сохранялся с {@code position_source=NULL}
 * и критерий навсегда оставался false для канонического демо-сценария
 * (VP-BQR / SU1234). Тест проходит через реальный входной порт (не INSERT в БД),
 * чтобы доказать, что fallback в {@code EventProcessorService.extractPositionSource}
 * закрывает этот разрыв на проде, а не только в искусственно подготовленных тестах.
 */
@DisplayName("Position report ingestion via MessageInputPort (regression: P1-1 review)")
class PositionReportIngestionIntTest extends BaseIntegrationTest {

    @Autowired
    private MessageInputPort messageInputPort;

    @Autowired
    private SequenceManagementUseCase sequenceUseCase;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    private static final String FLIGHT_NUMBER = "SU1234";

    private Long createPositionReportedSequence(String name) {
        SequenceCreateRequest createReq = new SequenceCreateRequest(name, "Регрессия P1-1: position_source fallback", null, null);
        SequenceResponse created = sequenceUseCase.createSequence(createReq, 1L);
        sequenceUseCase.addStep(created.id(), new StepCreateRequest(
                "Позиция получена?",
                StepType.EVALUATE,
                "{\"type\":\"POSITION_REPORTED\",\"minutesAgo\":30}",
                null,
                TransitionAction.END, null, false,
                TransitionAction.ABORT, null, false
        ), 1L);
        sequenceUseCase.activateSequence(created.id(), 1L);
        return created.id();
    }

    private ExecutionInstance findInstance(Long sequenceId, String aircraftId) {
        return executionRepository.findActiveByAircraftId(aircraftId).stream()
                .filter(i -> i.getSequenceId().equals(sequenceId))
                .findFirst()
                .or(() -> executionRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 200))
                        .getContent().stream()
                        .filter(i -> i.getSequenceId().equals(sequenceId) && aircraftId.equals(i.getAircraftId()))
                        .findFirst())
                .orElseThrow(() -> new AssertionError("No instance found for sequence " + sequenceId));
    }

    @Test
    @DisplayName("без явного positionSource (как реально шлёт фронтенд) — fallback проставляет ACARS, критерий закрывается")
    void positionReportWithoutExplicitSourceClosesPositionCriterion() {
        String aircraftId = "VP-BQR";

        // Ровно такая metadata, какую реально отправляют DemoPage.tsx / MessageSimulator.tsx
        // (быстрый пресет "Позиция") — без positionSource.
        Map<String, Object> frontendMetadata = Map.of(
                "latitude", 55.7558,
                "longitude", 37.6173
        );

        messageInputPort.receiveMessage(
                MessageType.DOWNLINK,
                "POSITION_REPORT",
                aircraftId,
                FLIGHT_NUMBER,
                "POS LAT=55.7558 LON=37.6173",
                frontendMetadata
        );

        Long sequenceId = createPositionReportedSequence("POSITION fallback - no explicit source");
        executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

        ExecutionInstance instance = findInstance(sequenceId, aircraftId);
        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("с явным positionSource в metadata — критерий закрывается как и раньше")
    void positionReportWithExplicitSourceClosesPositionCriterion() {
        String aircraftId = "VP-BQR-EXPLICIT";

        Map<String, Object> metadataWithSource = Map.of(
                "latitude", 55.7558,
                "longitude", 37.6173,
                "positionSource", "ACARS"
        );

        messageInputPort.receiveMessage(
                MessageType.DOWNLINK,
                "POSITION_REPORT",
                aircraftId,
                FLIGHT_NUMBER,
                "POS LAT=55.7558 LON=37.6173",
                metadataWithSource
        );

        Long sequenceId = createPositionReportedSequence("POSITION fallback - explicit source");
        executionService.startExecution(sequenceId, aircraftId, FLIGHT_NUMBER);

        ExecutionInstance instance = findInstance(sequenceId, aircraftId);
        assertThat(instance.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }
}
