package ru.protectinfotrans.eca.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.MessageType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-6: unit-тесты {@link DeadLetterQueueService} — захват сбойного входящего в DLQ + ручной
 * reprocess (успех/повторный сбой) на мокнутых портах. Реальная REQUIRES_NEW-транзакционная
 * семантика {@code captureFailure} (персист переживает откат вызывающей транзакции) проверяется
 * интеграционным тестом на Postgres.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadLetterQueueService")
class DeadLetterQueueServiceTest {

    @Mock
    private DeadLetterRepositoryPort repository;

    @Mock
    private RawMessageParserService rawMessageParserService;

    @Mock
    private MessageInputPort messageInputPort;

    @Mock
    private RawMessageIngestSupport ingestSupport;

    private SimpleMeterRegistry meterRegistry;

    private DeadLetterQueueService service;

    /**
     * production-конфигурация ObjectMapper приходит из автоконфигурации Spring Boot
     * (Jackson2ObjectMapperBuilder) — регистрирует {@link JavaTimeModule} И отключает
     * {@code WRITE_DATES_AS_TIMESTAMPS} автоматически (spring-boot-starter-json на classpath,
     * см. pom.xml), поэтому {@code LocalDate} сериализуется как ISO-строка ("2026-06-01"), а не
     * массив [2026,6,1]. Здесь воспроизводим то же самое явно для unit-теста (без полного
     * Spring-контекста).
     */
    private static ObjectMapper testObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new DeadLetterQueueService(repository, rawMessageParserService, messageInputPort,
                ingestSupport, testObjectMapper(), meterRegistry);
    }

    @Nested
    @DisplayName("captureFailure")
    class CaptureFailure {

        @Test
        @DisplayName("сохраняет DLQ-запись с форматом/сырым телом/причиной из исключения, статус NEW")
        void capturesAllFieldsFromFailure() {
            MessageParsingException error = new MessageParsingException(RawMessageFormat.AFTN, "не найдена строка priority");
            when(repository.save(any())).thenAnswer(invocation -> {
                DeadLetterMessage m = invocation.getArgument(0);
                m.setId(1L);
                return m;
            });

            DeadLetterMessage saved = service.captureFailure(RawMessageFormat.AFTN, "GARBAGE TEXT",
                    new DeadLetterRequestContext("UUEE", "ULLI", LocalDate.of(2026, 6, 1)), error);

            assertThat(saved.getId()).isEqualTo(1L);

            ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
            verify(repository).save(captor.capture());
            DeadLetterMessage persisted = captor.getValue();

            assertThat(persisted.getSource()).isEqualTo(DeadLetterSource.RAW_GATEWAY);
            assertThat(persisted.getFormat()).isEqualTo("AFTN");
            assertThat(persisted.getRawPayload()).isEqualTo("GARBAGE TEXT");
            assertThat(persisted.getReason()).contains("MessageParsingException").contains("не найдена строка priority");
            assertThat(persisted.getStackTrace()).isNotBlank();
            assertThat(persisted.getRequestContext()).contains("UUEE").contains("ULLI").contains("2026-06-01");
        }

        @Test
        @DisplayName("format=null (сбой до определения формата) -> не бросает NPE, format персистится как null")
        void nullFormatDoesNotThrow() {
            RuntimeException error = new IllegalStateException("unexpected failure");
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.captureFailure(null, "RAW", null, error);

            ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getFormat()).isNull();
            assertThat(captor.getValue().getRequestContext()).isNull();
        }

        @Test
        @DisplayName("стектрейс длиннее 8000 символов -> обрезается (не растит таблицу безгранично)")
        void longStackTraceIsTruncated() {
            RuntimeException deepError = buildDeepStackTraceException(500);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.captureFailure(RawMessageFormat.TYPE_B, "RAW", null, deepError);

            ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStackTrace()).hasSizeLessThanOrEqualTo(8000);
        }

        private RuntimeException buildDeepStackTraceException(int depth) {
            RuntimeException error = new RuntimeException("deep");
            StackTraceElement[] elements = new StackTraceElement[depth];
            for (int i = 0; i < depth; i++) {
                elements[i] = new StackTraceElement("SomeClass" + i, "method" + i, "SomeClass" + i + ".java", i);
            }
            error.setStackTrace(elements);
            return error;
        }
    }

    @Nested
    @DisplayName("list / getOrThrow")
    class ListAndGet {

        @Test
        @DisplayName("status=null -> findAll, иначе findByStatus")
        void listDelegatesToCorrectRepositoryMethod() {
            Pageable pageable = PageRequest.of(0, 20);
            Page<DeadLetterMessage> page = Page.empty();
            when(repository.findAll(pageable)).thenReturn(page);
            when(repository.findByStatus(DeadLetterStatus.NEW, pageable)).thenReturn(page);

            service.list(null, pageable);
            service.list(DeadLetterStatus.NEW, pageable);

            verify(repository).findAll(pageable);
            verify(repository).findByStatus(DeadLetterStatus.NEW, pageable);
        }

        @Test
        @DisplayName("getOrThrow: записи нет -> NoSuchElementException")
        void getOrThrowThrowsWhenMissing() {
            when(repository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getOrThrow(404L))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("reprocess")
    class Reprocess {

        @Test
        @DisplayName("успешный reprocess -> повторно парсит/принимает, markReprocessed с messageId")
        void successfulReprocessMarksReprocessed() {
            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(1L)
                    .source(DeadLetterSource.RAW_GATEWAY)
                    .format("ARINC_618")
                    .rawPayload("AN/VP-BQR FI/SU1234 LABEL/H1 TEXT")
                    .requestContext(null)
                    .status(DeadLetterStatus.NEW)
                    .attempts(0)
                    .build();
            when(repository.findById(1L)).thenReturn(Optional.of(entry));

            ParsedMessage parsed = new ParsedMessage(MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, entry.getRawPayload())).thenReturn(parsed);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(messageInputPort.receiveMessage(eq(MessageType.DOWNLINK), eq("H1"), eq("VP-BQR"), eq("SU1234"),
                    eq("TEXT"), isNull())).thenReturn(42L);

            boolean result = service.reprocess(1L);

            assertThat(result).isTrue();
            verify(repository).markReprocessed(eq(1L), eq(42L), any(LocalDateTime.class));
            verify(repository, never()).markReprocessFailed(any(), any(), any(), any());
        }

        @Test
        @DisplayName("повторный сбой парсинга при reprocess -> markReprocessFailed, статус НЕ переходит в REPROCESSED")
        void failedReprocessMarksReprocessFailed() {
            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(2L)
                    .source(DeadLetterSource.RAW_GATEWAY)
                    .format("AFTN")
                    .rawPayload("STILL GARBAGE")
                    .status(DeadLetterStatus.NEW)
                    .attempts(1)
                    .build();
            when(repository.findById(2L)).thenReturn(Optional.of(entry));

            MessageParsingException stillBroken =
                    new MessageParsingException(RawMessageFormat.AFTN, "ещё сломано");
            when(rawMessageParserService.parse(RawMessageFormat.AFTN, "STILL GARBAGE")).thenThrow(stillBroken);

            boolean result = service.reprocess(2L);

            assertThat(result).isFalse();
            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            verify(repository).markReprocessFailed(eq(2L), reasonCaptor.capture(), any(), any(LocalDateTime.class));
            assertThat(reasonCaptor.getValue()).contains("MessageParsingException").contains("ещё сломано");
            verify(repository, never()).markReprocessed(any(), any(), any());
            verify(messageInputPort, never()).receiveMessage(any(), any(), any(), any(), any(), anyMap());
        }

        @Test
        @DisplayName("DLQ-запись без формата -> reprocess сразу проваливается (IllegalStateException), markReprocessFailed")
        void reprocessWithoutFormatFailsImmediately() {
            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(3L)
                    .source(DeadLetterSource.STRUCTURED_GATEWAY)
                    .format(null)
                    .rawPayload("whatever")
                    .status(DeadLetterStatus.NEW)
                    .attempts(0)
                    .build();
            when(repository.findById(3L)).thenReturn(Optional.of(entry));

            boolean result = service.reprocess(3L);

            assertThat(result).isFalse();
            verify(repository).markReprocessFailed(eq(3L), any(), any(), any());
            verify(rawMessageParserService, never()).parse(any(), any());
        }

        @Test
        @DisplayName("reprocess неизвестного id -> NoSuchElementException, ничего не вызывается")
        void reprocessUnknownIdThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reprocess(99L)).isInstanceOf(NoSuchElementException.class);

            verify(rawMessageParserService, never()).parse(any(), any());
            verify(repository, never()).markReprocessed(any(), any(), any());
            verify(repository, never()).markReprocessFailed(any(), any(), any(), any());
        }

        @Test
        @DisplayName("requestContext из JSON десериализуется и передаётся в resolveFlightId/airports")
        void requestContextDeserializedAndForwarded() throws Exception {
            String contextJson = testObjectMapper().writeValueAsString(
                    new DeadLetterRequestContext("UUEE", "ULLI", LocalDate.of(2026, 1, 1)));

            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(4L)
                    .format("ARINC_618")
                    .rawPayload("AN/VP-BQR FI/AFL1234 LABEL/H1 TEXT")
                    .requestContext(contextJson)
                    .status(DeadLetterStatus.NEW)
                    .attempts(0)
                    .build();
            when(repository.findById(4L)).thenReturn(Optional.of(entry));

            ParsedMessage parsed = new ParsedMessage(MessageType.DOWNLINK, "H1", "VP-BQR", "AFL1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, entry.getRawPayload())).thenReturn(parsed);
            when(ingestSupport.resolveFlightId(eq(parsed), eq("UUEE"), eq("ULLI"), eq(LocalDate.of(2026, 1, 1))))
                    .thenReturn("SU1234");
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(messageInputPort.receiveMessage(any(), any(), any(), eq("SU1234"), any(), any())).thenReturn(7L);

            boolean result = service.reprocess(4L);

            assertThat(result).isTrue();
            verify(ingestSupport).resolveFlightId(parsed, "UUEE", "ULLI", LocalDate.of(2026, 1, 1));
        }
    }

    @Nested
    @DisplayName("discard")
    class Discard {

        @Test
        @DisplayName("discard: запись существует -> markDiscarded")
        void discardMarksDiscardedWhenEntryExists() {
            when(repository.findById(5L)).thenReturn(Optional.of(DeadLetterMessage.builder().id(5L).build()));

            service.discard(5L);

            verify(repository).markDiscarded(5L);
        }

        @Test
        @DisplayName("discard: записи нет -> NoSuchElementException, markDiscarded не вызывается")
        void discardThrowsWhenEntryMissing() {
            when(repository.findById(6L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.discard(6L)).isInstanceOf(NoSuchElementException.class);

            verify(repository, never()).markDiscarded(any());
        }
    }

    /**
     * P2-6: минимальный набор Micrometer-счётчиков на DLQ-пути (по аналогии с
     * {@code OutboundMessageDeliveryScheduler}) — проверяем через {@link SimpleMeterRegistry},
     * что {@code eca.integration.dlq.*} инкрементируются в нужных точках, без проверки полного
     * каталога метрик (это вне зоны ответственности этого сервиса).
     */
    @Nested
    @DisplayName("метрики")
    class Metrics {

        private double counterValue(String name) {
            return meterRegistry.get(name).counter().count();
        }

        @Test
        @DisplayName("captureFailure инкрементирует eca.integration.dlq.captured")
        void captureFailureIncrementsCapturedCounter() {
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            service.captureFailure(RawMessageFormat.AFTN, "GARBAGE",
                    null, new IllegalStateException("boom"));

            assertThat(counterValue("eca.integration.dlq.captured")).isEqualTo(1.0);
            assertThat(counterValue("eca.integration.dlq.reprocessed")).isEqualTo(0.0);
            assertThat(counterValue("eca.integration.dlq.reprocess.failed")).isEqualTo(0.0);
            assertThat(counterValue("eca.integration.dlq.discarded")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("успешный reprocess инкрементирует eca.integration.dlq.reprocessed")
        void successfulReprocessIncrementsReprocessedCounter() {
            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(10L)
                    .source(DeadLetterSource.RAW_GATEWAY)
                    .format("ARINC_618")
                    .rawPayload("AN/VP-BQR FI/SU1234 LABEL/H1 TEXT")
                    .status(DeadLetterStatus.NEW)
                    .attempts(0)
                    .build();
            when(repository.findById(10L)).thenReturn(Optional.of(entry));

            ParsedMessage parsed = new ParsedMessage(MessageType.DOWNLINK, "H1", "VP-BQR", "SU1234", "TEXT", null, null);
            when(rawMessageParserService.parse(RawMessageFormat.ARINC_618, entry.getRawPayload())).thenReturn(parsed);
            when(ingestSupport.resolveFlightId(parsed, null, null, null)).thenReturn("SU1234");
            when(ingestSupport.buildMetadata(parsed)).thenReturn(null);
            when(messageInputPort.receiveMessage(eq(MessageType.DOWNLINK), eq("H1"), eq("VP-BQR"), eq("SU1234"),
                    eq("TEXT"), isNull())).thenReturn(42L);

            service.reprocess(10L);

            assertThat(counterValue("eca.integration.dlq.reprocessed")).isEqualTo(1.0);
            assertThat(counterValue("eca.integration.dlq.reprocess.failed")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("повторный сбой reprocess инкрементирует eca.integration.dlq.reprocess.failed")
        void failedReprocessIncrementsReprocessFailedCounter() {
            DeadLetterMessage entry = DeadLetterMessage.builder()
                    .id(11L)
                    .source(DeadLetterSource.RAW_GATEWAY)
                    .format("AFTN")
                    .rawPayload("STILL GARBAGE")
                    .status(DeadLetterStatus.NEW)
                    .attempts(1)
                    .build();
            when(repository.findById(11L)).thenReturn(Optional.of(entry));
            when(rawMessageParserService.parse(RawMessageFormat.AFTN, "STILL GARBAGE"))
                    .thenThrow(new MessageParsingException(RawMessageFormat.AFTN, "ещё сломано"));

            service.reprocess(11L);

            assertThat(counterValue("eca.integration.dlq.reprocess.failed")).isEqualTo(1.0);
            assertThat(counterValue("eca.integration.dlq.reprocessed")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("discard инкрементирует eca.integration.dlq.discarded")
        void discardIncrementsDiscardedCounter() {
            when(repository.findById(12L)).thenReturn(Optional.of(DeadLetterMessage.builder().id(12L).build()));

            service.discard(12L);

            assertThat(counterValue("eca.integration.dlq.discarded")).isEqualTo(1.0);
        }
    }
}
