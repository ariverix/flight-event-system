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
import ru.protectinfotrans.eca.integration.application.DeadLetterQueueService;
import ru.protectinfotrans.eca.integration.application.DeadLetterRequestContext;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageIngestSupport;
import ru.protectinfotrans.eca.integration.parser.RawMessageParserService;

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
 * {@code RawMessageParserService}). <b>P2-6:</b> ДО переброса исходного исключения сообщение
 * сохраняется в DLQ ({@link DeadLetterQueueService#captureFailure}) — то же самое верно и для
 * непредвиденных ошибок самого приёма ({@code messageInputPort.receiveMessage} может бросить).
 * Внешняя система всё равно получает тот же явный 400/500, что и раньше (контракт не меняется),
 * но сообщение больше не теряется — оператор видит его в списке DLQ и может выполнить ручной
 * reprocess после устранения причины сбоя.
 *
 * <p><b>Callsign matching -> FI (P2-4, часть 2).</b> {@link ParsedMessage#flightNumber()} несёт
 * то, что разобрал форматный парсер (P2-2) из поля {@code FI/} тела телеграммы — на практике это
 * МОЖЕТ быть уже готовый внутренний flight id (IATA-style, напр. {@code "SU1234"}) ИЛИ сырой
 * ICAO-позывной (напр. {@code "AFL1234"}), который сама внешняя система ещё не привела к FI —
 * оба варианта реально встречаются (ТЗ: «бывает и IATA... но SITA callsign matching обычно по
 * ICAO carrier code»). После парсинга контроллер пробует callsign matching через
 * {@link RawMessageIngestSupport#resolveFlightId} (P2-6: вынесено из этого класса в общий шаг
 * конвейера, переиспользуемый ручным reprocess DLQ-записи — см. javadoc
 * {@link RawMessageIngestSupport}): если значение распознаётся как позывной (код
 * перевозчика+номер) И находится действующее правило — {@code flightNumber} ЗАМЕНЯЕТСЯ на
 * найденный FI перед передачей в {@link MessageInputPort#receiveMessage} (тот FI и есть привязка
 * последовательности по паритету SITA). Если правило не найдено (либо значение не похоже на
 * позывной, либо ни одно правило не подошло по периоду/дню/аэропортам) — {@code flightNumber}
 * передаётся БЕЗ ИЗМЕНЕНИЙ, как и до P2-4: привязка по AN (tail number) либо по уже-валидному FI
 * продолжает работать как раньше, матчинг здесь НИЧЕГО не ломает и не выдумывает FI при
 * отсутствии правил.
 */
@Tag(name = "Messages", description = "Приём сырых сообщений «борт-земля» (P2-2: ARINC 618/620, Type B, AFTN)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class RawMessageController {

    private final RawMessageParserService rawMessageParserService;
    private final MessageInputPort messageInputPort;
    private final RawMessageIngestSupport ingestSupport;
    private final DeadLetterQueueService deadLetterQueueService;

    /**
     * Принять сырое (нераспарсенное) сообщение в одном из промышленных форматов «борт-земля».
     *
     * @param request заявленный формат + сырой текст телеграммы/ACARS-сообщения
     * @return ID сохранённого сообщения
     */
    @Operation(summary = "Принять сырое сообщение (ARINC 618/620, Type B, AFTN)",
               description = "P2-2: нормализация промышленного формата борт-земля в структуру и передача "
                       + "в общий конвейер приёма (идемпотентность/persist — P2-1). P2-4: позывной в "
                       + "FI/-поле тела телеграммы при наличии правила сопоставляется с flight id (FI) "
                       + "через callsign matching table. Не требует аутентификации.")
    @ApiResponse(responseCode = "200", description = "Сообщение распарсено, принято и обработано")
    @ApiResponse(responseCode = "400", description = "Сообщение не соответствует заявленному формату")
    @PostMapping("/messages/incoming/raw")
    public ResponseEntity<RawMessageReceivedResponse> receiveRawMessage(
            @Valid @RequestBody RawIncomingMessageRequest request) {
        log.debug("POST /api/v1/messages/incoming/raw: format={}", request.format());

        try {
            ParsedMessage parsed = rawMessageParserService.parse(request.format(), request.rawMessage());

            Map<String, Object> metadata = ingestSupport.buildMetadata(parsed);
            String flightId = ingestSupport.resolveFlightId(parsed, request.departureAirport(),
                    request.arrivalAirport(), request.flightDate());

            Long messageId = messageInputPort.receiveMessage(
                    parsed.messageType(),
                    parsed.templateName(),
                    parsed.aircraftId(),
                    flightId,
                    parsed.payload(),
                    metadata
            );

            return ResponseEntity.ok(new RawMessageReceivedResponse(messageId, "Raw message parsed, received and processed"));
        } catch (RuntimeException failure) {
            // P2-6: НЕ теряем сырое сообщение — persist в DLQ ДО переброса исходного исключения
            // (контракт ответа клиенту не меняется, см. javadoc класса).
            deadLetterQueueService.captureFailure(request.format(), request.rawMessage(),
                    new DeadLetterRequestContext(request.departureAirport(), request.arrivalAirport(),
                            request.flightDate()),
                    failure);
            throw failure;
        }
    }

    /**
     * Ответ на приём сырого сообщения.
     */
    public record RawMessageReceivedResponse(Long messageId, String status) {}
}
