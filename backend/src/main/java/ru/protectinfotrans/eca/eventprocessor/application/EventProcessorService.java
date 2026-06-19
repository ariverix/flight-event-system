package ru.protectinfotrans.eca.eventprocessor.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.FlightStage;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.event.NormalizedEvent;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.EventPublisherPort;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Принимает сообщения от внешних систем, сохраняет в БД
 * и публикует NormalizedEvent для остальных модулей.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class EventProcessorService implements MessageInputPort {

    private final MessageRepositoryPort messageRepository;
    private final EventPublisherPort eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public Long receiveMessage(
            MessageType messageType,
            String templateName,
            String aircraftId,
            String flightNumber,
            String content,
            Map<String, Object> metadata
    ) {
        log.info("Receiving message: type={}, template={}, aircraft={}, flight={}",
                messageType, templateName, aircraftId, flightNumber);

        IncomingMessage message = IncomingMessage.builder()
                .messageType(messageType)
                .templateName(templateName)
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .content(content)
                .metadataJson(serializeMetadata(metadata))
                .receivedAt(LocalDateTime.now())
                .positionSource(extractPositionSource(metadata))
                .estimatedPosition(extractEstimatedFlag(metadata))
                .build();

        message = messageRepository.save(message);
        log.debug("Message saved with ID: {}", message.getId());

        FlightStage flightStage = extractFlightStage(metadata);

        NormalizedEvent event = new NormalizedEvent(
                message.getId(),
                messageType,
                templateName,
                aircraftId,
                flightNumber,
                flightStage,
                message.getReceivedAt()
        );

        eventPublisher.publish(event);
        log.info("NormalizedEvent published for message ID: {}", message.getId());

        return message.getId();
    }

    @Override
    public void notifyFlightStageChange(String aircraftId, String flightNumber, FlightStage stage) {
        log.info("Flight stage change: aircraft={}, flight={}, stage={}", aircraftId, flightNumber, stage);

        // смена стадии — системное событие, в таблицу messages не пишем
        NormalizedEvent event = new NormalizedEvent(
                null,
                null,
                null,
                aircraftId,
                flightNumber,
                stage,
                LocalDateTime.now()
        );

        eventPublisher.publish(event);
        log.info("Flight stage change event published: {}", stage);
    }

    private FlightStage extractFlightStage(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("flightStage")) {
            return null;
        }

        try {
            String stageName = (String) metadata.get("flightStage");
            return FlightStage.valueOf(stageName);
        } catch (Exception e) {
            log.warn("Failed to extract flight stage from metadata", e);
            return null;
        }
    }

    /**
     * Источник позиционного отчёта (ACARS/RADAR/ADS_B) — паритет с SITA Sequencer.
     * Null для немпозиционных сообщений (отсутствие ключа в metadata И отсутствие
     * признаков позиционных данных).
     * <p>
     * Защитный fallback: если клиент (UI/внешняя система) прислал координаты
     * (latitude/longitude) в metadata, но явно не указал positionSource, сообщение
     * всё равно является позиционным отчётом по сути — проставляем дефолтный ACARS
     * и предупреждаем в лог, чтобы не терять позиционные данные молча
     * (POSITION_REPORTED-критерий требует positionSource IS NOT NULL — P1-1).
     */
    private PositionSource extractPositionSource(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }

        if (metadata.containsKey("positionSource")) {
            try {
                String sourceName = (String) metadata.get("positionSource");
                return PositionSource.valueOf(sourceName);
            } catch (Exception e) {
                log.warn("Failed to extract position source from metadata", e);
                return null;
            }
        }

        if (metadata.containsKey("latitude") || metadata.containsKey("longitude")) {
            log.warn("Position report metadata contains latitude/longitude but no explicit "
                    + "positionSource — defaulting to {} so the POSITION_REPORTED criterion "
                    + "(requires position_source IS NOT NULL) does not silently ignore this message",
                    PositionSource.ACARS);
            return PositionSource.ACARS;
        }

        return null;
    }

    /**
     * Признак оценочной (estimated) позиции. По умолчанию false (фактическая) —
     * консервативный дефолт, чтобы не терять фактические позиции без явной пометки.
     */
    private boolean extractEstimatedFlag(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey("estimatedPosition")) {
            return false;
        }

        Object value = metadata.get("estimatedPosition");
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metadata", e);
            return null;
        }
    }
}
