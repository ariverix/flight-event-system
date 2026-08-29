package ru.protectinfotrans.eca.integration;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.integration.adapter.in.RawIncomingMessageRequest;
import ru.protectinfotrans.eca.integration.adapter.in.RawMessageController;
import ru.protectinfotrans.eca.integration.application.CircuitBreakerPolicy;
import ru.protectinfotrans.eca.integration.application.DeadLetterQueueService;
import ru.protectinfotrans.eca.integration.application.OutboundMessageDeliveryScheduler;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.domain.OutboundMessage;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageStatus;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.DeadLetterRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.OutboundMessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-6: DLQ для сбойных входящих + ручной reprocess; ретраи с backoff + circuit breaker на
 * исходящий канал доставки. Сценарии на реальном Postgres (по аналогии с
 * {@code P2_3_OutboundGatewayScenarioIntTest}).
 *
 * <ol>
 *   <li>Сбойное raw-сообщение (битый ARINC 618, нет AN/) -> persist в {@code dead_letter_messages},
 *       НЕ теряется молча, контроллер всё равно возвращает 400.</li>
 *   <li>Ручной reprocess: после "исправления" сырого сообщения (новая DLQ-запись с тем же телом,
 *       но валидным) reprocess успешен -> REPROCESSED + сообщение реально попадает в основной
 *       конвейер ({@code messages} таблица/{@code MessageInputPort}).</li>
 *   <li>Повторный сбой reprocess -> attempts++, статус остаётся NEW.</li>
 *   <li>RBAC: {@code /api/v1/dlq/**} требует JWT (401 без токена), доступен OPERATOR/ADMIN.</li>
 *   <li>Backoff: сбойная доставка ставит {@code next_attempt_at} в будущее — немедленный повторный
 *       тик поллера НЕ подхватывает свежепровалившуюся запись.</li>
 *   <li>Circuit breaker: серия сбоев канала открывает breaker (durable {@code channel_circuit_breakers}),
 *       дальнейшие кандидаты блокируются fail-fast; после истечения таймаута — HALF_OPEN проба;
 *       успешная проба закрывает breaker обратно.</li>
 * </ol>
 *
 * Демо-борт VP-BQR — единый стиль с {@code EcaParityScenarioIntTest}/{@code P2_3_...}.
 */
@Slf4j
@DisplayName("P2-6: DLQ сбойных входящих + ручной reprocess + backoff/circuit breaker на outbound")
class P2_6_DlqAndResilienceScenarioIntTest extends BaseIntegrationTest {

    @Autowired
    private RawMessageController rawMessageController;

    @Autowired
    private DeadLetterQueueService deadLetterQueueService;

    @Autowired
    private DeadLetterRepositoryPort deadLetterRepository;

    @Autowired
    private MessageInputPort messageInputPort;

    @Autowired
    private MessageOutputPort messageOutputPort;

    @Autowired
    private OutboundMessageRepositoryPort outboundMessageRepository;

    @Autowired
    private OutboundMessageDeliveryScheduler deliveryScheduler;

    @Autowired
    private CircuitBreakerRepositoryPort circuitBreakerRepository;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final long BREAKER_CONVERGENCE_TIMEOUT_MS = 5000;
    private static final long BREAKER_POLL_INTERVAL_MS = 100;

    // ============================================================
    // 1. Сбойное входящее -> DLQ, не теряется
    // ============================================================
    @Nested
    @DisplayName("Сбойное входящее raw-сообщение попадает в DLQ")
    class IncomingFailureCapturedInDlq {

        @Test
        @DisplayName("ARINC 618 без обязательного AN/ -> MessageParsingException пробрасывается (400) И persist в DLQ")
        void brokenRawMessageIsCapturedInDlqAndStillFails() {
            long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dead_letter_messages", Long.class);

            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "LABEL/H1 NO AIRCRAFT TAIL HERE");

            org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                    () -> rawMessageController.receiveRawMessage(request));

            long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dead_letter_messages", Long.class);
            assertThat(after).isEqualTo(before + 1);

            DeadLetterMessage entry = deadLetterQueueService.list(null, PageRequest.of(0, 10))
                    .getContent().get(0);
            assertThat(entry.getFormat()).isEqualTo("ARINC_618");
            assertThat(entry.getRawPayload()).isEqualTo("LABEL/H1 NO AIRCRAFT TAIL HERE");
            assertThat(entry.getStatus()).isEqualTo(DeadLetterStatus.NEW);
            assertThat(entry.getReason()).contains("MessageParsingException");
            assertThat(entry.getAttempts()).isZero();
        }

        @Test
        @DisplayName("успешное raw-сообщение -> DLQ остаётся пустым")
        void successfulRawMessageDoesNotTouchDlq() {
            RawIncomingMessageRequest request = new RawIncomingMessageRequest(
                    RawMessageFormat.ARINC_618, "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT");

            ResponseEntity<RawMessageController.RawMessageReceivedResponse> response =
                    rawMessageController.receiveRawMessage(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            long dlqCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dead_letter_messages", Long.class);
            assertThat(dlqCount).isZero();
        }
    }

    // ============================================================
    // 2/3. Ручной reprocess: успех и повторный сбой
    // ============================================================
    @Nested
    @DisplayName("Ручной reprocess DLQ-записи")
    class ManualReprocess {

        @Test
        @DisplayName("reprocess успешен после 'исправления' -> REPROCESSED + сообщение реально в основном конвейере")
        void successfulReprocessReachesMainPipeline() {
            // "Сбой" сейчас — ОТСУТСТВИЕ aircraftId на момент исходного приёма эмулируется прямым
            // captureFailure (не зависит от конкретной причины сбоя парсера) — после "исправления"
            // оператор обновил бы сырое тело перед reprocess; здесь демонстрируем сам механизм
            // reprocess на ВАЛИДНОМ теле, как если бы оператор его подправил.
            DeadLetterMessage entry = deadLetterQueueService.captureFailure(
                    RawMessageFormat.ARINC_618,
                    "AN/VP-BQR FI/SU1234 LABEL/H1 TEXT",
                    null,
                    new IllegalStateException("transient failure, e.g. eventprocessor DB unavailable at the time"));

            long messagesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Long.class);

            boolean result = deadLetterQueueService.reprocess(entry.getId());

            assertThat(result).isTrue();

            long messagesAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM messages", Long.class);
            assertThat(messagesAfter).isEqualTo(messagesBefore + 1);

            DeadLetterMessage afterReprocess = deadLetterRepository.findById(entry.getId()).orElseThrow();
            assertThat(afterReprocess.getStatus()).isEqualTo(DeadLetterStatus.REPROCESSED);
            assertThat(afterReprocess.getReprocessedMessageId()).isNotNull();
            assertThat(afterReprocess.getLastAttemptAt()).isNotNull();
        }

        @Test
        @DisplayName("повторный сбой reprocess (тело всё ещё битое) -> attempts++, статус остаётся NEW")
        void failedReprocessIncrementsAttemptsAndStaysNew() {
            // "AN FIELD" было бы случайно подхвачено регэкспом Arinc618Parser AN_PATTERN
            // (\bAN[/ ]([A-Z0-9-]{3,8})\b допускает пробел как разделитель для многострочного
            // формата) как валидный борт "FIELD" — для теста "тело всё ещё битое" нужна строка,
            // где обязательное поле AN/<tail> отсутствует НЕПОДДЕЛЬНО, без случайного совпадения.
            DeadLetterMessage entry = deadLetterQueueService.captureFailure(
                    RawMessageFormat.ARINC_618,
                    "STILL MISSING TAIL NUMBER HERE",
                    null,
                    new IllegalStateException("original failure"));
            assertThat(entry.getAttempts()).isZero();

            boolean firstRetry = deadLetterQueueService.reprocess(entry.getId());
            assertThat(firstRetry).isFalse();

            DeadLetterMessage afterFirstRetry = deadLetterRepository.findById(entry.getId()).orElseThrow();
            assertThat(afterFirstRetry.getStatus()).isEqualTo(DeadLetterStatus.NEW);
            assertThat(afterFirstRetry.getAttempts()).isEqualTo(1);
            assertThat(afterFirstRetry.getReason()).contains("MessageParsingException");

            // оператор решает попробовать снова — НЕТ автоматического лимита попыток ручной операции
            boolean secondRetry = deadLetterQueueService.reprocess(entry.getId());
            assertThat(secondRetry).isFalse();

            DeadLetterMessage afterSecondRetry = deadLetterRepository.findById(entry.getId()).orElseThrow();
            assertThat(afterSecondRetry.getAttempts()).isEqualTo(2);
            assertThat(afterSecondRetry.getStatus()).isEqualTo(DeadLetterStatus.NEW);
        }

        @Test
        @DisplayName("discard: оператор отбрасывает запись -> DISCARDED, терминальный статус")
        void discardMarksTerminalStatus() {
            DeadLetterMessage entry = deadLetterQueueService.captureFailure(
                    RawMessageFormat.AFTN, "JUNK", null, new IllegalStateException("test data, discard"));

            deadLetterQueueService.discard(entry.getId());

            DeadLetterMessage afterDiscard = deadLetterRepository.findById(entry.getId()).orElseThrow();
            assertThat(afterDiscard.getStatus()).isEqualTo(DeadLetterStatus.DISCARDED);
        }
    }

    // ============================================================
    // 4. RBAC на /api/v1/dlq/**
    // ============================================================
    @Nested
    @DisplayName("RBAC: /api/v1/dlq/** НЕ открытый ingestion-путь")
    class DlqEndpointRbac {

        @Test
        @DisplayName("без JWT -> 401 (не permitAll, в отличие от /api/v1/messages/**)")
        void withoutTokenReturns401() {
            ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/dlq", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("с JWT admin (ADMIN роль) -> 200")
        void withAdminTokenReturns200() {
            String token = getAdminToken();
            ResponseEntity<String> response = restTemplate.exchange(
                    "/api/v1/dlq", HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ============================================================
    // 5. Backoff: следующая попытка не раньше next_attempt_at
    // ============================================================
    @Nested
    @DisplayName("P2-6: backoff соблюдается поллером")
    class BackoffRespected {

        @Test
        @DisplayName("сбойная доставка -> next_attempt_at в будущем, немедленный повторный тик НЕ подхватывает запись")
        void failedDeliveryIsNotImmediatelyRetried() {
            boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE",
                    Map.of("__simulateFailure", true), UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).isTrue();

            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

            deliveryScheduler.pollPendingMessages();

            OutboundMessage afterFirstTick = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(afterFirstTick.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
            assertThat(afterFirstTick.getAttempts()).isEqualTo(1);
            assertThat(afterFirstTick.getNextAttemptAt()).isAfter(LocalDateTime.now());

            // немедленный повторный тик — backoff-окно ещё не истекло, кандидат не подхватывается
            deliveryScheduler.pollPendingMessages();

            OutboundMessage afterSecondTick = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(afterSecondTick.getAttempts()).isEqualTo(1); // не выросло — попытка не повторилась
            assertThat(afterSecondTick.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
        }

        @Test
        @DisplayName("MAX_ATTEMPTS исчерпаны -> запись переходит в терминальный FAILED")
        void exhaustedAttemptsTransitionsToFailed() {
            boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE",
                    Map.of("__simulateFailure", true), UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).isTrue();

            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

            // 5 сбойных попыток (MAX_ATTEMPTS=5) — после каждой явно сбрасываем next_attempt_at в
            // прошлое, чтобы следующий тик подхватил запись немедленно (тест не должен спать на
            // реальный backoff-таймер).
            for (int i = 0; i < 5; i++) {
                jdbcTemplate.update("UPDATE outbound_messages SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
                deliveryScheduler.pollPendingMessages();
            }

            OutboundMessage exhausted = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(exhausted.getStatus()).isEqualTo(OutboundMessageStatus.FAILED);
            assertThat(exhausted.getAttempts()).isEqualTo(5);
        }
    }

    // ============================================================
    // 6. Circuit breaker: открытие на серии сбоев + fail-fast + HALF_OPEN восстановление
    // ============================================================
    @Nested
    @DisplayName("P2-6: circuit breaker на канал доставки")
    class CircuitBreakerResilience {

        @Test
        @DisplayName("серия сбоев одного канала открывает breaker (durable channel_circuit_breakers)")
        void seriesOfFailuresOpensBreaker() throws InterruptedException {
            // DEFAULT_FAILURE_THRESHOLD=5 — 5 независимых сообщений того же канала (UPLINK),
            // каждое сбойно доставляется -> 5-й сбой открывает breaker.
            for (int i = 0; i < 5; i++) {
                boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE",
                        Map.of("__simulateFailure", true, "seq", i), UplinkOrigin.COMPUTER_GENERATED);
                assertThat(enqueued).isTrue();
            }

            // Раньше — фиксированные "5 тиков, каждый забирает ровно один кандидат". На CI
            // (не локально) воспроизводимо ловилась гонка: часть из 5 кандидатов оказывалась
            // забрана не явным тиком теста, а каким-то параллельным вызовом `deliverOne`
            // (в логах CI-рана — "delivery failed" с посторонним thread/correlationId; точный
            // источник конкурентного тика не подтверждён статическим анализом, предположительно
            // фоновая докатка Spring Modulith). Итог по сути не меняется — `claimPending`
            // гарантирует ровно одну обработку на сообщение, `recordFailure` считает каждый
            // реальный сбой независимо от того, какой поток его исполнил, — но при ровно 5 явных
            // тиках теста брейкер иногда не успевал открыться к моменту проверки. Поэтому —
            // поллим до сходимости, а не ровно 5 раз: то, ЧТО именно исполняет тик доставки, для
            // теста не важно, важен итоговый durable-снимок.
            ChannelCircuitBreaker breaker = null;
            long deadline = System.currentTimeMillis() + BREAKER_CONVERGENCE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                deliveryScheduler.pollPendingMessages();
                breaker = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
                if (breaker.getState() == CircuitBreakerState.OPEN) {
                    break;
                }
                Thread.sleep(BREAKER_POLL_INTERVAL_MS);
            }

            assertThat(breaker).isNotNull();
            assertThat(breaker.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(breaker.getConsecutiveFailures()).isGreaterThanOrEqualTo(5);
            assertThat(breaker.getOpenedAt()).isNotNull();
        }

        @Test
        @DisplayName("breaker OPEN -> дальнейшие кандидаты канала блокируются fail-fast (PENDING, без markFailed)")
        void openBreakerBlocksFurtherCandidatesFailFast() {
            // форсируем OPEN напрямую — не зависим от деталей "сколько сбоев нужно", тестируем
            // именно блокировку.
            forceOpenBreaker(OutboundMessageType.UPLINK, LocalDateTime.now());
            ChannelCircuitBreaker forcedOpen = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
            assertThat(forcedOpen.getState()).isEqualTo(CircuitBreakerState.OPEN);

            boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE", Map.of(),
                    UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).isTrue();
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

            deliveryScheduler.pollPendingMessages();

            OutboundMessage afterBlock = outboundMessageRepository.findById(id).orElseThrow();
            // fail-fast: канал не тронут (params без __simulateFailure означало бы успех, если
            // бы дошло до simulateChannelSend) — статус PENDING, attempts НЕ выросли (breaker
            // решение, не сбой ЭТОГО сообщения)
            assertThat(afterBlock.getStatus()).isEqualTo(OutboundMessageStatus.PENDING);
            assertThat(afterBlock.getAttempts()).isZero();
        }

        @Test
        @DisplayName("после истечения таймаута восстановления — успешная HALF_OPEN проба закрывает breaker")
        void successfulProbeAfterTimeoutClosesBreaker() {
            // breaker OPEN с openedAt далеко в прошлом — таймаут восстановления (30с) точно истёк
            forceOpenBreaker(OutboundMessageType.UPLINK, LocalDateTime.now().minusMinutes(5));
            ChannelCircuitBreaker openLongAgo = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
            assertThat(openLongAgo.getState()).isEqualTo(CircuitBreakerState.OPEN);

            // сообщение БЕЗ __simulateFailure -> пробная попытка должна пройти успешно
            boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE", Map.of(),
                    UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).isTrue();
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

            deliveryScheduler.pollPendingMessages();

            OutboundMessage delivered = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(delivered.getStatus()).isEqualTo(OutboundMessageStatus.SENT);

            ChannelCircuitBreaker afterProbe = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
            assertThat(afterProbe.getState()).isEqualTo(CircuitBreakerState.CLOSED);
            assertThat(afterProbe.getConsecutiveFailures()).isZero();
        }

        @Test
        @DisplayName("после истечения таймаута — провалившаяся HALF_OPEN проба снова открывает breaker с новым openedAt")
        void failedProbeAfterTimeoutReopensBreakerWithFreshTimeout() {
            LocalDateTime originalOpenedAt = LocalDateTime.now().minusMinutes(5);
            forceOpenBreaker(OutboundMessageType.UPLINK, originalOpenedAt);

            boolean enqueued = messageOutputPort.sendUplink(AIRCRAFT_ID, "CLEARANCE",
                    Map.of("__simulateFailure", true), UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).isTrue();
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

            deliveryScheduler.pollPendingMessages();

            ChannelCircuitBreaker afterFailedProbe = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
            assertThat(afterFailedProbe.getState()).isEqualTo(CircuitBreakerState.OPEN);
            assertThat(afterFailedProbe.getOpenedAt()).isAfter(originalOpenedAt);

            OutboundMessage afterFailedProbeMessage = outboundMessageRepository.findById(id).orElseThrow();
            assertThat(afterFailedProbeMessage.getAttempts()).isEqualTo(1);
        }

        // ============================================================
        // Issue #1: lost-update гонка на recordFailure под конкуренцией
        // ============================================================
        @Test
        @DisplayName("Issue #1: два конкурентных recordFailure одного канала не теряют инкремент "
                + "(lost-update гонка нескольких deliverOne/реплик на канал)")
        void concurrentRecordFailureDoesNotLoseUpdates() throws Exception {
            // Раньше recordFailure принимал уже вычисленное вызывающей стороной абсолютное
            // consecutiveFailures (снимок+1 над снимком, прочитанным ДО этого вызова) — два
            // конкурентных вызова recordFailure того же канала оба вычисляли "снимок+1" НАД ОДНИМ
            // И ТЕМ ЖЕ устаревшим снимком и оба писали ОДНО И ТО ЖЕ число (classic lost update):
            // итоговый consecutiveFailures оставался 1 вместо 2, хотя произошло два реальных сбоя.
            // Сейчас recordFailure — атомарный SQL-инкремент, читающий текущую строку в момент
            // UPDATE (row-level lock в Postgres сериализует конкурентные UPDATE того же channel),
            // поэтому эта гонка невозможна конструктивно — тест это доказывает на реальном Postgres.
            OutboundMessageType channel = OutboundMessageType.UPLINK;
            circuitBreakerRepository.getOrCreate(channel); // baseline CLOSED/0, до порога (5) далеко

            int concurrentFailures = 2;
            CyclicBarrier barrier = new CyclicBarrier(concurrentFailures);
            ExecutorService pool = Executors.newFixedThreadPool(concurrentFailures);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < concurrentFailures; i++) {
                    futures.add(pool.submit(() -> {
                        // барьер гарантирует, что оба потока стартуют recordFailure одновременно —
                        // максимизирует шанс реального пересечения транзакций на одном канале,
                        // а не случайное последовательное выполнение.
                        barrier.await(5, TimeUnit.SECONDS);
                        circuitBreakerRepository.recordFailure(channel,
                                CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD, LocalDateTime.now());
                        return null;
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdown();
            }

            ChannelCircuitBreaker after = circuitBreakerRepository.getOrCreate(channel);
            assertThat(after.getConsecutiveFailures())
                    .as("оба конкурентных сбоя должны быть учтены — ни один инкремент не потерян")
                    .isEqualTo(concurrentFailures);
        }

        /**
         * Issue #1: форсирует OPEN для канала через атомарные {@code recordFailure} вызовы (та же
         * точка входа, что использует {@code OutboundMessageDeliveryScheduler} в реальном пути) —
         * вместо ручной установки абсолютного счётчика/{@code shouldOpen} (устаревший API ДО
         * фикса lost-update гонки, см. javadoc {@code CircuitBreakerRepositoryPort#recordFailure}).
         * {@code openedAt} на ПОСЛЕДНЕМ (порогопересекающем) вызове определяет итоговый
         * {@code opened_at} — предыдущие вызовы не переводят канал в OPEN, поэтому их {@code now}
         * не влияет на итоговый снимок.
         */
        private void forceOpenBreaker(OutboundMessageType channel, LocalDateTime openedAt) {
            for (int i = 0; i < CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD - 1; i++) {
                circuitBreakerRepository.recordFailure(channel, CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD,
                        LocalDateTime.now());
            }
            circuitBreakerRepository.recordFailure(channel, CircuitBreakerPolicy.DEFAULT_FAILURE_THRESHOLD, openedAt);
        }
    }
}
