package ru.protectinfotrans.eca.eventprocessor.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.application.EventProcessorService;
import ru.protectinfotrans.eca.eventprocessor.application.MessageQueryService;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.dto.FlightStageChangeRequest;
import ru.protectinfotrans.eca.eventprocessor.dto.IncomingMessageRequest;
import ru.protectinfotrans.eca.eventprocessor.dto.MessageResponse;

/**
 * REST контроллер для приёма входящих сообщений от внешних систем.
 * Реализует UC-06 (Обработать входящее сообщение).
 *
 * Гексагональная архитектура: REST-адаптер (Driving Adapter) — вызывает входной порт MessageInputPort.
 *
 * См. диплом: раздел 1.3.5 (UC-06), раздел 1.4.4 (таблица 1.6)
 */
@Tag(name = "Messages", description = "Приём входящих сообщений и журнал (UC-06)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class MessageController {

    private final EventProcessorService eventProcessorService;
    private final MessageQueryService messageQueryService;

    /**
     * UC-06: Принять входящее сообщение от внешней системы.
     * Доступ: permitAll (внешние системы без аутентификации).
     *
     * @param request данные входящего сообщения
     * @return ID сохранённого сообщения
     */
    @Operation(summary = "Принять входящее сообщение",
               description = "UC-06: Принять ACARS-сообщение от внешней системы. Не требует аутентификации.")
    @ApiResponse(responseCode = "200", description = "Сообщение принято и обработано")
    @PostMapping("/messages/incoming")
    public ResponseEntity<MessageReceivedResponse> receiveMessage(@Valid @RequestBody IncomingMessageRequest request) {
        log.debug("POST /api/v1/messages/incoming: type={}, template={}, aircraft={}",
                request.messageType(), request.templateName(), request.aircraftId());

        Long messageId = eventProcessorService.receiveMessage(
                request.messageType(),
                request.templateName(),
                request.aircraftId(),
                request.flightNumber(),
                request.content(),
                request.metadata()
        );

        return ResponseEntity.ok(new MessageReceivedResponse(messageId, "Message received and processed"));
    }

    /**
     * UC-06: Уведомить об изменении стадии полёта.
     * Доступ: permitAll (внешние системы без аутентификации).
     *
     * @param request данные изменения стадии
     */
    @Operation(summary = "Изменение стадии полёта",
               description = "UC-06: Уведомить систему об изменении стадии OOOI. Не требует аутентификации.")
    @ApiResponse(responseCode = "200", description = "Стадия обновлена")
    @PostMapping("/flights/stage-change")
    public ResponseEntity<Void> notifyFlightStageChange(@Valid @RequestBody FlightStageChangeRequest request) {
        log.debug("POST /api/v1/flights/stage-change: aircraft={}, stage={}",
                request.aircraftId(), request.stage());

        eventProcessorService.notifyFlightStageChange(
                request.aircraftId(),
                request.flightNumber(),
                request.stage()
        );

        return ResponseEntity.ok().build();
    }

    /**
     * Получить журнал сообщений с фильтрами и пагинацией.
     * Доступ: OPERATOR, ADMIN (требует аутентификации).
     *
     * @param aircraftId фильтр по ВС (опционально)
     * @param messageType фильтр по типу сообщения (опционально)
     * @param pageable параметры пагинации (page, size, sort)
     * @return страница сообщений
     */
    @Operation(summary = "Журнал сообщений",
               description = "Получить список входящих сообщений с фильтрацией по ВС и типу. Требует аутентификации.")
    @GetMapping("/messages")
    public ResponseEntity<Page<MessageResponse>> getMessages(
            @RequestParam(required = false) String aircraftId,
            @RequestParam(required = false) MessageType messageType,
            Pageable pageable
    ) {
        log.debug("GET /api/v1/messages: aircraftId={}, messageType={}, page={}",
                aircraftId, messageType, pageable.getPageNumber());

        Page<IncomingMessage> messages = messageQueryService.findMessages(aircraftId, messageType, pageable);

        Page<MessageResponse> response = messages.map(msg -> new MessageResponse(
                msg.getId(),
                msg.getMessageType(),
                msg.getTemplateName(),
                msg.getAircraftId(),
                msg.getFlightNumber(),
                msg.getContent(),
                msg.getReceivedAt()
        ));

        return ResponseEntity.ok(response);
    }

    /**
     * Вспомогательный record для ответа при приёме сообщения.
     */
    public record MessageReceivedResponse(Long messageId, String status) {}
}
