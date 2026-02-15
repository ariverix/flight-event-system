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

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Сервис обработки входящих событий.
 * Реализует UC-06: Обработать входящее сообщение.
 *
 * Основные обязанности:
 * - Приём сообщений от внешних систем через REST API
 * - Сохранение в БД (таблица messages)
 * - Классификация и нормализация
 * - Публикация NormalizedEvent через Spring Events
 *
 * См. диплом: раздел 1.3.5 (UC-06), раздел 1.4.3 (поток входящих событий)
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class EventProcessorService implements MessageInputPort {

    private final MessageRepositoryPort messageRepository;
    private final EventPublisherPort eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * UC-06: Принять входящее сообщение от внешней системы.
     */
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

        // 1. Сохранить сообщение в БД
        IncomingMessage message = IncomingMessage.builder()
                .messageType(messageType)
                .templateName(templateName)
                .aircraftId(aircraftId)
                .flightNumber(flightNumber)
                .content(content)
                .metadataJson(serializeMetadata(metadata))
                .receivedAt(LocalDateTime.now())
                .build();

        message = messageRepository.save(message);
        log.debug("Message saved with ID: {}", message.getId());

        // 2. Классифицировать и нормализовать
        FlightStage flightStage = extractFlightStage(metadata);

        // 3. Опубликовать NormalizedEvent
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

    /**
     * UC-06: Уведомить об изменении стадии полёта.
     */
    @Override
    public void notifyFlightStageChange(String aircraftId, String flightNumber, FlightStage stage) {
        log.info("Flight stage change: aircraft={}, flight={}, stage={}", aircraftId, flightNumber, stage);

        // Публикуем событие без сохранения сообщения в БД
        // (изменение стадии — это не сообщение, а системное событие)
        NormalizedEvent event = new NormalizedEvent(
                null,  // messageId = null для stage-change
                null,  // messageType = null
                null,  // templateName = null
                aircraftId,
                flightNumber,
                stage,
                LocalDateTime.now()
        );

        eventPublisher.publish(event);
        log.info("Flight stage change event published: {}", stage);
    }

    /**
     * Извлечь стадию полёта из метаданных сообщения.
     */
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
     * Сериализовать метаданные в JSON.
     */
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
