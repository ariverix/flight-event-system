package ru.protectinfotrans.eca.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterSource;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.parser.MessageParsingException;
import ru.protectinfotrans.eca.integration.parser.ParsedMessage;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;
import ru.protectinfotrans.eca.integration.parser.RawMessageIngestSupport;
import ru.protectinfotrans.eca.integration.parser.RawMessageParserService;
import ru.protectinfotrans.eca.integration.port.out.DeadLetterRepositoryPort;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * P2-6: DLQ (Dead Letter Queue) для сбойных входящих сообщений «борт-земля».
 *
 * <p><b>Точка захвата:</b> {@code RawMessageController#receiveRawMessage} перехватывает
 * {@link MessageParsingException} (битое/неполное сообщение, не соответствует заявленному
 * формату — P2-2) и непредвиденные ошибки самого приёма ({@code MessageInputPort#receiveMessage}
 * может бросить, например, если {@code eventprocessor} временно недоступен/БД constraint),
 * вызывает {@link #captureFailure}, и ТОЛЬКО ПОСЛЕ этого перебрасывает исходное исключение —
 * вызывающая внешняя ACARS-система видит тот же 400/500 ответ, что и раньше (контракт P2-2 не
 * меняется), но теперь сообщение НЕ потеряно — оно в {@code dead_letter_messages} для оператора.
 *
 * <p><b>Почему {@code captureFailure} в {@code REQUIRES_NEW}:</b> на момент перехвата (внутри
 * REST-контроллера, который сам не открывает явную транзакцию для самого захвата ошибки) либо нет
 * активной транзакции вовсе, либо — для пути ошибки САМОГО {@code receiveMessage} — та транзакция
 * уже помечена rollback-only/откатилась (та же причина, что в {@code EventProcessorService},
 * см. её javadoc): персист DLQ-записи должен быть гарантированно независимой, успешно
 * закоммиченной транзакцией, иначе откат бизнес-операции откатил бы и сам факт фиксации сбоя —
 * ровно то самое "не теряем сообщение" превратилось бы в фикцию.
 *
 * <p><b>Ручной reprocess ({@link #reprocess}):</b> повторно прогоняет {@code rawPayload} через
 * ТОТ ЖЕ {@link RawMessageParserService} + {@link MessageInputPort#receiveMessage}, что и обычный
 * raw-путь приёма (P2-2/P2-1) — если исходная причина сбоя устранена (например внешняя система
 * было прислала телеграмму с лишним пробелом и оператор подправил вручную перед reprocess,
 * либо сбой был транзиентным — БД была недоступна), запись переходит в {@code REPROCESSED} с
 * привязкой к итоговому {@code messageId}; при повторном сбое — {@code attempts++}, новая
 * причина, статус остаётся {@code NEW} (оператор решает сам, пробовать ли снова — не вводим
 * автоматический лимит попыток ручной операции, см. javadoc
 * {@code DeadLetterRepositoryPort#markReprocessFailed}).
 *
 * <p><b>Метрики (P2-6, по аналогии с {@code OutboundMessageDeliveryScheduler}):</b>
 * {@code eca.integration.dlq.captured} — каждая DLQ-запись, зафиксированная {@link #captureFailure};
 * {@code eca.integration.dlq.reprocessed} — успешный {@link #reprocess}; {@code eca.integration.dlq.reprocess.failed}
 * — повторный сбой при {@link #reprocess}; {@code eca.integration.dlq.discarded} — {@link #discard}.
 * Минимальный набор для нового пути — полный каталог метрик вне зоны этого сервиса (observability-agent).
 */
@Service
@Slf4j
public class DeadLetterQueueService {

    private final DeadLetterRepositoryPort repository;
    private final RawMessageParserService rawMessageParserService;
    private final MessageInputPort messageInputPort;
    private final RawMessageIngestSupport ingestSupport;
    private final ObjectMapper objectMapper;
    private final Counter capturedCounter;
    private final Counter reprocessedCounter;
    private final Counter reprocessFailedCounter;
    private final Counter discardedCounter;

    public DeadLetterQueueService(DeadLetterRepositoryPort repository,
                                   RawMessageParserService rawMessageParserService,
                                   MessageInputPort messageInputPort,
                                   RawMessageIngestSupport ingestSupport,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry) {
        this.repository = repository;
        this.rawMessageParserService = rawMessageParserService;
        this.messageInputPort = messageInputPort;
        this.ingestSupport = ingestSupport;
        this.objectMapper = objectMapper;
        this.capturedCounter = meterRegistry.counter("eca.integration.dlq.captured");
        this.reprocessedCounter = meterRegistry.counter("eca.integration.dlq.reprocessed");
        this.reprocessFailedCounter = meterRegistry.counter("eca.integration.dlq.reprocess.failed");
        this.discardedCounter = meterRegistry.counter("eca.integration.dlq.discarded");
        // P5-1: текущий размер DLQ (записи в статусе NEW — ждут решения оператора). Опрашивается
        // на каждый scrape через countByStatus (индексируется, нечастый запрос).
        meterRegistry.gauge("eca.integration.dlq.size", repository,
                repo -> (double) repo.countByStatus(DeadLetterStatus.NEW));
    }

    /**
     * Персистит DLQ-запись о сбойном RAW-сообщении в собственной, независимой транзакции.
     *
     * @param format         заявленный формат (может быть {@code null}, если сбой произошёл до
     *                       определения формата — практически не встречается на этом пути, но
     *                       сигнатура не должна предполагать NPE)
     * @param rawMessage     сырое тело телеграммы — нужно полностью для ручного reprocess
     * @param requestContext исходный контекст запроса (departureAirport/arrivalAirport/flightDate,
     *                       P2-4 часть 2) — сериализуется в JSON и используется при reprocess,
     *                       чтобы повторный прогон вёл себя идентично оригинальному приёму
     * @param error          исключение, вызвавшее сбой (парсинг либо приём)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeadLetterMessage captureFailure(RawMessageFormat format, String rawMessage,
                                             DeadLetterRequestContext requestContext, Throwable error) {
        DeadLetterMessage entry = DeadLetterMessage.builder()
                .source(DeadLetterSource.RAW_GATEWAY)
                .format(format == null ? null : format.name())
                .rawPayload(rawMessage)
                .requestContext(serializeContext(requestContext))
                .reason(describeError(error))
                .stackTrace(stackTraceOf(error))
                .correlationId(CorrelationContext.getCorrelationId())
                .build();

        DeadLetterMessage saved = repository.save(entry);
        capturedCounter.increment();
        log.error("Incoming message moved to DLQ: id={}, format={}, reason={}",
                saved.getId(), format, saved.getReason());
        return saved;
    }

    /** Список DLQ-записей для оператора, опционально отфильтрованный по статусу. */
    @Transactional(readOnly = true)
    public Page<DeadLetterMessage> list(DeadLetterStatus status, Pageable pageable) {
        return status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public DeadLetterMessage getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("DLQ-запись не найдена: id=" + id));
    }

    /**
     * Ручной повторный прогон DLQ-записи через конвейер приёма. Контракт не-{@code @Transactional}
     * умышленно (по тому же принципу, что {@code EventProcessorService#receiveMessage}, P2-1):
     * сам разбор/приём (через {@link RawMessageParserService}/{@link MessageInputPort}) открывает
     * и коммитит/откатывает СВОИ собственные транзакции независимо от этого метода — отметка
     * результата ({@code markReprocessed}/{@code markReprocessFailed}) на DLQ-записи выполняется
     * ПОСЛЕ того, как соответствующая попытка приёма уже завершилась (успешно или с исключением),
     * в отдельной короткой транзакции внутри JPA-репозитория.
     *
     * @param id идентификатор DLQ-записи
     * @return {@code true}, если reprocess завершился успехом ({@code REPROCESSED})
     */
    public boolean reprocess(Long id) {
        DeadLetterMessage entry = getOrThrow(id);
        LocalDateTime attemptAt = LocalDateTime.now();

        try {
            RawMessageFormat format = entry.getFormat() == null ? null : RawMessageFormat.valueOf(entry.getFormat());
            if (format == null) {
                throw new IllegalStateException("DLQ-запись без формата не может быть повторно разобрана: id=" + id);
            }

            ParsedMessage parsed = rawMessageParserService.parse(format, entry.getRawPayload());

            DeadLetterRequestContext context = deserializeContext(entry.getRequestContext());
            String flightId = ingestSupport.resolveFlightId(parsed,
                    context == null ? null : context.departureAirport(),
                    context == null ? null : context.arrivalAirport(),
                    context == null ? null : context.flightDate());
            Map<String, Object> metadata = ingestSupport.buildMetadata(parsed);

            Long messageId = messageInputPort.receiveMessage(
                    parsed.messageType(),
                    parsed.templateName(),
                    parsed.aircraftId(),
                    flightId,
                    parsed.payload(),
                    metadata
            );

            repository.markReprocessed(id, messageId, attemptAt);
            reprocessedCounter.increment();
            log.info("DLQ entry {} reprocessed successfully -> messageId={}", id, messageId);
            return true;
        } catch (Exception e) {
            log.error("DLQ entry {} reprocess attempt failed", id, e);
            repository.markReprocessFailed(id, describeError(e), stackTraceOf(e), attemptAt);
            reprocessFailedCounter.increment();
            return false;
        }
    }

    /** Ручное решение оператора — DLQ-запись больше не подлежит reprocess. */
    @Transactional
    public void discard(Long id) {
        getOrThrow(id); // 404, если записи нет
        repository.markDiscarded(id);
        discardedCounter.increment();
        log.info("DLQ entry {} discarded by operator", id);
    }

    private String serializeContext(DeadLetterRequestContext context) {
        if (context == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.warn("Failed to serialize DLQ request context, storing null", e);
            return null;
        }
    }

    private DeadLetterRequestContext deserializeContext(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DeadLetterRequestContext.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize DLQ request context, proceeding without it", e);
            return null;
        }
    }

    private String describeError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private String stackTraceOf(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String full = sw.toString();
        // ограничиваем размер — стектрейс не должен расти таблицу безгранично на повторяющихся сбоях
        return full.length() > 8000 ? full.substring(0, 8000) : full;
    }
}
