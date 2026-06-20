package ru.protectinfotrans.eca.integration.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageParserService;

import java.util.HashMap;
import java.util.Map;

/**
 * REST-адаптер приёма СЫРОГО (нераспарсенного) сообщения «борт-земля» (P2-2): ARINC 618/620,
 * Type B, AFTN.
 *
 * <p><b>Расположение в модуле {@code integration} (не {@code eventprocessor}):</b> парсинг
 * (формат -> структура) — это зона ответственности Integration Adapter (см. {@code TEAM.md}:
 * «ACARS/AFTN/Type B, ARINC-парсинг» — {@code integration-dev}). Если бы этот контроллер вместо
 * этого жил в {@code eventprocessor} и оттуда вызывал {@code integration.parser.RawMessageParserService},
 * возник бы цикл границ модулей: {@code eventprocessor -> integration -> execution -> eventprocessor}
 * (модуль {@code execution} уже зависит от {@code eventprocessor.event.NormalizedEvent}, а
 * {@code integration} уже зависит от {@code execution.port.out.*} для исходящих сообщений/уведомлений) —
 * {@code ApplicationModules.verify()} такой цикл не пропускает. Поэтому зависимость идёт в обратную,
 * непротиворечивую сторону: {@code integration -> eventprocessor} через публичный входной порт
 * {@link MessageInputPort} (named interface {@code port-in}) — та же точка входа, которой пользуется
 * структурированный путь {@code eventprocessor.adapter.in.MessageController#receiveMessage}.
 *
 * <p>Доступ: permitAll, как и структурированный путь ({@code /api/v1/messages/**} в
 * {@code SecurityConfig}) — открытый ingestion-эндпоинт, внешние ACARS/AFTN-машины не умеют в JWT,
 * защита сетевая (mTLS/allowlist).
 *
 * <p>Ошибка парсинга (битое/неполное сообщение, не соответствует заявленному формату) НЕ роняет
 * запрос 500-кой и не теряет сообщение молча: {@code MessageParsingException} — подтип
 * {@link IllegalArgumentException}, перехватывается общим {@code GlobalExceptionHandler.handleBadRequest}
 * и возвращается как структурированный {@code ProblemDetail} 400 (детали — в логе ERROR на стороне
 * {@code RawMessageParserService}, задел под DLQ — P2-6).
 */
@Tag(name = "Messages", description = "Приём сырых сообщений «борт-земля» (P2-2: ARINC 618/620, Type B, AFTN)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class RawMessageController {

    private final RawMessageParserService rawMessageParserService;
    private final MessageInputPort messageInputPort;

    /**
     * Принять сырое (нераспарсенное) сообщение в одном из промышленных форматов «борт-земля».
     *
     * @param request заявленный формат + сырой текст телеграммы/ACARS-сообщения
     * @return ID сохранённого сообщения
     */
    @Operation(summary = "Принять сырое сообщение (ARINC 618/620, Type B, AFTN)",
               description = "P2-2: нормализация промышленного формата борт-земля в структуру и передача "
                       + "в общий конвейер приёма (идемпотентность/persist — P2-1). Не требует аутентификации.")
    @ApiResponse(responseCode = "200", description = "Сообщение распарсено, принято и обработано")
    @ApiResponse(responseCode = "400", description = "Сообщение не соответствует заявленному формату")
    @PostMapping("/messages/incoming/raw")
    public ResponseEntity<RawMessageReceivedResponse> receiveRawMessage(
            @Valid @RequestBody RawIncomingMessageRequest request) {
        log.debug("POST /api/v1/messages/incoming/raw: format={}", request.format());

        ParsedMessage parsed = rawMessageParserService.parse(request.format(), request.rawMessage());

        Map<String, Object> metadata = parsed.metadata() == null ? null : new HashMap<>(parsed.metadata());
        if (parsed.externalMessageId() != null && !parsed.externalMessageId().isBlank()) {
            metadata = metadata == null ? new HashMap<>() : metadata;
            metadata.put("externalMessageId", parsed.externalMessageId());
        }

        Long messageId = messageInputPort.receiveMessage(
                parsed.messageType(),
                parsed.templateName(),
                parsed.aircraftId(),
                parsed.flightNumber(),
                parsed.payload(),
                metadata
        );

        return ResponseEntity.ok(new RawMessageReceivedResponse(messageId, "Raw message parsed, received and processed"));
    }

    /**
     * Ответ на приём сырого сообщения.
     */
    public record RawMessageReceivedResponse(Long messageId, String status) {}
}
