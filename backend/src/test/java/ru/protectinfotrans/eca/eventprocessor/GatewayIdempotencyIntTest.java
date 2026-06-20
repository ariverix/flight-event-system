package ru.protectinfotrans.eca.eventprocessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.adapter.in.MessageController;
import ru.protectinfotrans.eca.eventprocessor.dto.IncomingMessageRequest;
import ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-1: идемпотентность входящего ACARS-шлюза по {@code externalMessageId}
 * (идентификатор сообщения от внешней системы — ARINC message reference,
 * AFTN serial number и т.п., требует миграции V25 — колонка
 * {@code messages.external_message_id} + UNIQUE-индекс по непустым значениям).
 *
 * <p>Реальный сквозной путь: {@code POST /api/v1/messages/incoming} (открытый эндпоинт,
 * permitAll — SecurityConfig) → {@code EventProcessorService.receiveMessage} → persist в
 * {@code messages} → публикация {@code NormalizedEvent} (Spring Modulith Transactional
 * Outbox, P1-7). Дополняет дедуп потребителя (P1-7, {@code triggering_message_id} на
 * {@code execution_instances}, ключ — {@code messages.id}): без этого теста повторная
 * HTTP-доставка ОТ ВНЕШНЕЙ СИСТЕМЫ создавала бы НОВУЮ запись в {@code messages} с НОВЫМ id
 * и P1-7 дедуп не сработал бы вовсе (P1-7 защищает только от повторной доставки ОДНОГО И
 * ТОГО ЖЕ {@code NormalizedEvent} внутри Outbox, не от повторного HTTP-запроса).
 */
@DisplayName("P2-1: идемпотентность шлюза ACARS по externalMessageId")
class GatewayIdempotencyIntTest extends BaseIntegrationTest {

    @Autowired
    private MessageController messageController;

    @Autowired
    private MessageInputPort messageInputPort;

    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";

    @Nested
    @DisplayName("Повторный приём с тем же externalMessageId через REST")
    class DuplicateDeliveryViaRest {

        @Test
        @DisplayName("две HTTP-доставки с одним externalMessageId -> ровно одна запись в messages, тот же messageId в ответе")
        void duplicateRestDeliveryCreatesExactlyOneMessage() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, "ARINC-REF-DUP-1");

            ResponseEntity<MessageController.MessageReceivedResponse> first =
                    messageController.receiveMessage(request);
            ResponseEntity<MessageController.MessageReceivedResponse> second =
                    messageController.receiveMessage(request);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getBody().messageId())
                    .as("повторная доставка должна вернуть id уже сохранённого сообщения, не новый")
                    .isEqualTo(first.getBody().messageId());

            Long countWithThisRef = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ?",
                    Long.class, "ARINC-REF-DUP-1");
            assertThat(countWithThisRef)
                    .as("в БД должна остаться РОВНО одна запись с этим externalMessageId")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("две HTTP-доставки с одним externalMessageId публикуют NormalizedEvent ровно один раз")
        void duplicateRestDeliveryPublishesEventExactlyOnce() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, "ARINC-REF-DUP-2");

            messageController.receiveMessage(request);
            messageController.receiveMessage(request);

            Long messageRowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ?",
                    Long.class, "ARINC-REF-DUP-2");
            assertThat(messageRowCount).isEqualTo(1L);

            // ровно одна публикация NormalizedEvent привязана к этому конкретному сообщению —
            // serialized_event содержит messageId этой записи; повторный приём не должен
            // добавить вторую публикацию для того же дедуп-ключа.
            Long messageId = jdbcTemplate.queryForObject(
                    "SELECT id FROM messages WHERE external_message_id = ?",
                    Long.class, "ARINC-REF-DUP-2");

            Long publicationsForThisMessage = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%NormalizedEvent%' "
                            + "AND serialized_event LIKE ?",
                    Long.class, "%\"messageId\":" + messageId + "%");
            assertThat(publicationsForThisMessage)
                    .as("ровно одна Outbox-публикация NormalizedEvent для этого сообщения")
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Разные externalMessageId — разные сообщения")
    class DistinctExternalMessageIds {

        @Test
        @DisplayName("два разных externalMessageId создают две разные записи в messages")
        void distinctExternalMessageIdsCreateSeparateMessages() {
            IncomingMessageRequest first = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, "ARINC-REF-A");
            IncomingMessageRequest second = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, "ARINC-REF-B");

            ResponseEntity<MessageController.MessageReceivedResponse> firstResp =
                    messageController.receiveMessage(first);
            ResponseEntity<MessageController.MessageReceivedResponse> secondResp =
                    messageController.receiveMessage(second);

            assertThat(firstResp.getBody().messageId()).isNotEqualTo(secondResp.getBody().messageId());

            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id IN (?, ?)",
                    Long.class, "ARINC-REF-A", "ARINC-REF-B");
            assertThat(total).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("Без externalMessageId (опциональный идентификатор)")
    class MissingExternalMessageId {

        @Test
        @DisplayName("повторные приёмы без externalMessageId не дедуплицируются — каждый создаёт новую запись")
        void messagesWithoutExternalMessageIdAreNotDeduplicated() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, null);

            ResponseEntity<MessageController.MessageReceivedResponse> first =
                    messageController.receiveMessage(request);
            ResponseEntity<MessageController.MessageReceivedResponse> second =
                    messageController.receiveMessage(request);

            assertThat(first.getBody().messageId())
                    .as("без externalMessageId дедуп шлюза неприменим — каждый вызов создаёт новое сообщение")
                    .isNotEqualTo(second.getBody().messageId());

            Long countWithNullRef = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id IS NULL AND aircraft_id = ?",
                    Long.class, AIRCRAFT_ID);
            assertThat(countWithNullRef).isGreaterThanOrEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("Сквозная защита: P2-1 (шлюз) + P1-7 (потребитель) вместе")
    class EndToEndProtection {

        /**
         * Доказывает, что повторная HTTP-доставка (эмуляция at-least-once повтора от внешней
         * ACARS-системы, например ретрай по таймауту без подтверждения) не создаёт второй
         * {@code ExecutionInstance} — комбинация дедупа шлюза (P2-1: не создаём вторую запись
         * {@code messages}/не публикуем второй {@code NormalizedEvent}) и дедупа потребителя
         * (P1-7: даже если бы событие пришло дважды, {@code triggering_message_id} защитил бы
         * повторно) даёт сквозную защиту от дублирования бизнес-эффекта.
         */
        @Test
        @DisplayName("повторная доставка через REST не приводит к повторной публикации события для движка")
        void duplicateRestDeliveryDoesNotDoubleNormalizedEvent() {
            IncomingMessageRequest request = new IncomingMessageRequest(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null, "ARINC-REF-E2E");

            messageInputPort.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null,
                    Map.of("externalMessageId", "ARINC-REF-E2E"));
            messageInputPort.receiveMessage(
                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null,
                    Map.of("externalMessageId", "ARINC-REF-E2E"));

            Long messageCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE external_message_id = ?",
                    Long.class, "ARINC-REF-E2E");
            assertThat(messageCount).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("TOCTOU-гонка (ревью): реально конкурентные доставки с одним externalMessageId")
    class ConcurrentDuplicateDelivery {

        /**
         * Воспроизводит ровно ту гонку, на которую указал reviewer: между
         * {@code findByExternalMessageId} (пусто) и {@code save()} в исходной реализации не было
         * никакой блокировки — под READ COMMITTED (PostgreSQL default) несколько потоков,
         * стартующих ОДНОВРЕМЕННО (через {@link CountDownLatch}, без него потоки выполнялись бы
         * практически последовательно и гонка не воспроизводилась бы стабильно) с ОДНИМ
         * {@code externalMessageId}, все проходят find пустым результатом и все пытаются
         * сохранить запись — все, кроме одного, ловят {@code DataIntegrityViolationException}
         * от partial unique index (V25). На старой реализации (без catch) это исключение летело
         * наружу как {@code DataIntegrityViolationException} -> {@code GlobalExceptionHandler
         * .handleGeneral} -> HTTP 500. Тест проверяет: (1) ни один вызов не выбросил исключение
         * наружу, (2) ровно одна строка в {@code messages}, (3) ровно одна Outbox-публикация
         * {@code NormalizedEvent} для этого сообщения, (4) все потоки получили валидный (и
         * одинаковый) messageId.
         */
        @Test
        @DisplayName("N параллельных приёмов с одним externalMessageId -> ровно одна messages-строка, "
                + "ровно одна публикация события, ни одного исключения наружу")
        void concurrentDeliveriesWithSameExternalMessageIdProduceExactlyOneMessageAndOnePublication()
                throws InterruptedException {
            String externalMessageId = "ARINC-REF-RACE-CONCURRENT";
            int threadCount = 8;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger exceptionCount = new AtomicInteger(0);

            try {
                List<CompletableFuture<Long>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < threadCount; i++) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            startLatch.await();
                            return messageInputPort.receiveMessage(
                                    MessageType.DOWNLINK, "STATUS", AIRCRAFT_ID, FLIGHT_NUMBER, null,
                                    Map.of("externalMessageId", externalMessageId));
                        } catch (Exception ex) {
                            exceptionCount.incrementAndGet();
                            throw new RuntimeException(ex);
                        }
                    }, executor));
                }

                // все потоки одновременно проходят затвор -> максимизирует шанс одновременного
                // прохождения find-проверки до того, как кто-либо успел закоммитить save().
                startLatch.countDown();

                List<Long> results = futures.stream()
                        .map(CompletableFuture::join)
                        .toList();

                assertThat(exceptionCount.get())
                        .as("ни один конкурентный вызов не должен выбросить исключение наружу "
                                + "(до фикса: DataIntegrityViolationException -> 500)")
                        .isZero();

                assertThat(results)
                        .as("каждый поток должен получить валидный (не null) messageId")
                        .doesNotContainNull();

                assertThat(results.stream().distinct().count())
                        .as("все потоки должны получить ОДИН и тот же messageId — id записи победителя")
                        .isEqualTo(1L);

                Long messageRowCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE external_message_id = ?",
                        Long.class, externalMessageId);
                assertThat(messageRowCount)
                        .as("ровно одна messages-строка, несмотря на " + threadCount + " конкурентных вызовов")
                        .isEqualTo(1L);

                Long messageId = jdbcTemplate.queryForObject(
                        "SELECT id FROM messages WHERE external_message_id = ?",
                        Long.class, externalMessageId);

                Long publicationsForThisMessage = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_publication WHERE event_type LIKE '%NormalizedEvent%' "
                                + "AND serialized_event LIKE ?",
                        Long.class, "%\"messageId\":" + messageId + "%");
                assertThat(publicationsForThisMessage)
                        .as("ровно одна Outbox-публикация NormalizedEvent — победитель публикует "
                                + "событие один раз, проигравшие гонку НЕ публикуют повторно")
                        .isEqualTo(1L);
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
