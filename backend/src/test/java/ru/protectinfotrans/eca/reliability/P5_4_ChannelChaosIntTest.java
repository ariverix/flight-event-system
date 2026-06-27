package ru.protectinfotrans.eca.reliability;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.port.out.MessageOutputPort;
import ru.protectinfotrans.eca.integration.application.OutboundMessageDeliveryScheduler;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-4 Chaos: падение исходящего канала — circuit breaker CLOSED→OPEN + readiness 503 +
 * восстановление канала → HALF_OPEN→CLOSED + readiness UP.
 *
 * <p><b>TDD (P5-4, CLAUDE.md):</b> тест написан первым — документирует и верифицирует
 * поведение существующего механизма P2-6 в контексте наблюдаемости P5-3.
 *
 * <p><b>Опора на существующие механизмы (без переписывания):</b>
 * <ul>
 *   <li>Circuit breaker (P2-6): {@link CircuitBreakerPolicy}, {@code ChannelCircuitBreakerJpaAdapter},
 *       таблица {@code channel_circuit_breakers}.</li>
 *   <li>Readiness probe (P5-3): {@code IntegrationChannelsHealthIndicator} — OPEN breaker → DOWN →
 *       readiness группа возвращает HTTP 503.</li>
 *   <li>Имитация сбоя канала: {@code params.__simulateFailure = true} (существующий механизм
 *       {@code OutboundMessageDeliveryScheduler#simulateChannelSend}, P2-6).</li>
 * </ul>
 *
 * <p><b>Сценарий:</b>
 * <ol>
 *   <li>Исходное состояние: circuit breaker CLOSED, readiness UP.</li>
 *   <li>5 сбоев доставки UPLINK → breaker CLOSED→OPEN (DEFAULT_FAILURE_THRESHOLD=5).</li>
 *   <li>Readiness → DOWN (HTTP 503) через {@code IntegrationChannelsHealthIndicator}.</li>
 *   <li>Симуляция восстановления канала: сдвигаем {@code opened_at} на 5 мин в прошлое
 *       (таймаут восстановления 30с истёк).</li>
 *   <li>Успешная HALF_OPEN-проба (сообщение без __simulateFailure) → breaker CLOSED.</li>
 *   <li>Readiness → UP (HTTP 200).</li>
 * </ol>
 *
 * <p>Использует локальный PostgreSQL ({@link BaseIntegrationTest} / localhost:5432).
 * Flyway clean+migrate выполняется в {@code @BeforeEach} — каждый тест начинает с чистого состояния.
 *
 * <p><b>Scope-граница:</b> настоящий replica-failover → P6-1.
 *
 * @see ru.protectinfotrans.eca.integration.P2_6_DlqAndResilienceScenarioIntTest подробные unit/IT
 *     по самому circuit breaker (P2-6).
 * @see ru.protectinfotrans.eca.observability.P5_3_HealthProbesIntTest health readiness 503 при OPEN.
 */
@Slf4j
@AutoConfigureObservability
@DisplayName("P5-4 Chaos: channel failure → circuit breaker OPEN → readiness 503 → recovery → UP")
class P5_4_ChannelChaosIntTest extends BaseIntegrationTest {

    /** Демо-борт (стиль EcaParityScenarioIntTest). */
    private static final String AIRCRAFT_ID = "VP-BQR";

    @Autowired
    private MessageOutputPort messageOutputPort;

    @Autowired
    private OutboundMessageDeliveryScheduler deliveryScheduler;

    @Autowired
    private CircuitBreakerRepositoryPort circuitBreakerRepository;

    // ========================================================================
    // Сценарий 1: полный цикл CLOSED→OPEN→CLOSED через circuit breaker
    // ========================================================================

    @Test
    @DisplayName("Полный цикл: 5 сбоев → OPEN → readiness 503 → recovery → CLOSED → readiness UP")
    void channelFailureCycle_closedToOpenToClosedWithReadinessMirror() {
        // ── Шаг 1: начальное состояние ──────────────────────────────────────
        // После flyway.clean()+migrate() channel_circuit_breakers пуста;
        // getOrCreate() вернёт CLOSED с 0 сбоями.
        ChannelCircuitBreaker initial = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
        assertThat(initial.getState())
                .as("Исходное состояние circuit breaker — CLOSED")
                .isEqualTo(CircuitBreakerState.CLOSED);

        assertReadiness(200, "Начальное состояние: readiness UP");

        // ── Шаг 2: 5 последовательных сбоев доставки ────────────────────────
        // Ставим 5 UPLINK-сообщений с флагом симуляции сбоя.
        // Каждый тик поллера обрабатывает одно сообщение → 5 тиков = 5 сбоев.
        for (int i = 0; i < 5; i++) {
            boolean enqueued = messageOutputPort.sendUplink(
                    AIRCRAFT_ID, "CLEARANCE",
                    Map.of("__simulateFailure", true, "seq", i),
                    UplinkOrigin.COMPUTER_GENERATED);
            assertThat(enqueued).as("Сообщение %d должно быть поставлено в очередь", i).isTrue();
        }

        for (int i = 0; i < 5; i++) {
            deliveryScheduler.pollPendingMessages();
        }

        // ── Шаг 3: circuit breaker должен быть OPEN ─────────────────────────
        ChannelCircuitBreaker afterFailures = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
        assertThat(afterFailures.getState())
                .as("После 5 сбоев circuit breaker должен быть OPEN")
                .isEqualTo(CircuitBreakerState.OPEN);
        assertThat(afterFailures.getConsecutiveFailures())
                .as("Количество последовательных сбоев >= 5")
                .isGreaterThanOrEqualTo(5);
        assertThat(afterFailures.getOpenedAt())
                .as("openedAt должен быть установлен при открытии breaker")
                .isNotNull();

        log.info("P5-4 Chaos: breaker OPEN (failures={}). Проверяем readiness...",
                afterFailures.getConsecutiveFailures());

        // ── Шаг 4: readiness → DOWN (503) при OPEN breaker ──────────────────
        // IntegrationChannelsHealthIndicator (P5-3) видит OPEN breaker → DOWN.
        assertReadiness(503, "При OPEN circuit breaker readiness должна быть DOWN (HTTP 503)");

        // ── Шаг 5: симуляция восстановления канала ───────────────────────────
        // Перемещаем opened_at на 5 минут в прошлое: таймаут восстановления (30с) истёк.
        // Следующий тик поллера увидит ALLOW_PROBE и выполнит HALF_OPEN-попытку.
        jdbcTemplate.update("""
                UPDATE channel_circuit_breakers
                SET opened_at = NOW() - INTERVAL '5 minutes'
                WHERE channel = 'UPLINK'
                """);

        log.info("P5-4 Chaos: opened_at сдвинут на -5 мин. Отправляем успешное сообщение...");

        // ── Шаг 6: успешная HALF_OPEN-проба → CLOSED ────────────────────────
        // Сообщение без __simulateFailure → доставка успешна → breaker CLOSED.
        boolean enqueued = messageOutputPort.sendUplink(
                AIRCRAFT_ID, "CLEARANCE", Map.of(), UplinkOrigin.COMPUTER_GENERATED);
        assertThat(enqueued).as("HALF_OPEN-проба должна быть поставлена в очередь").isTrue();

        deliveryScheduler.pollPendingMessages();

        // ── Шаг 7: breaker должен быть CLOSED ────────────────────────────────
        ChannelCircuitBreaker afterRecovery = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
        assertThat(afterRecovery.getState())
                .as("После успешной HALF_OPEN-пробы circuit breaker должен быть CLOSED")
                .isEqualTo(CircuitBreakerState.CLOSED);
        assertThat(afterRecovery.getConsecutiveFailures())
                .as("Счётчик сбоев сброшен в 0 после успешной пробы")
                .isZero();

        log.info("P5-4 Chaos: breaker CLOSED. Проверяем readiness восстановление...");

        // ── Шаг 8: readiness → UP (200) ──────────────────────────────────────
        assertReadiness(200, "После закрытия circuit breaker readiness должна быть UP (HTTP 200)");
    }

    // ========================================================================
    // Сценарий 2: fail-fast при OPEN breaker (дополнительный сценарий)
    // ========================================================================

    @Test
    @DisplayName("OPEN breaker: дальнейшие сообщения канала блокируются fail-fast (PENDING, attempts=0)")
    void openBreaker_blocksFurtherDeliveries_failFast() {
        // Принудительно устанавливаем OPEN breaker через 6 recordFailure-вызовов
        // (последний с shouldOpen=true и openedAt=now) — быстро, без 5 реальных сбоев.
        // Паттерн из P2_6_DlqAndResilienceScenarioIntTest: всегда передаём LocalDateTime.now()
        // (openedAt используется только при shouldOpen=true, но параметр должен быть non-null).
        for (int i = 0; i < 5; i++) {
            circuitBreakerRepository.recordFailure(
                    OutboundMessageType.UPLINK, false, i + 1, LocalDateTime.now());
        }
        circuitBreakerRepository.recordFailure(
                OutboundMessageType.UPLINK, true, 5, LocalDateTime.now());

        ChannelCircuitBreaker forcedOpen = circuitBreakerRepository.getOrCreate(OutboundMessageType.UPLINK);
        assertThat(forcedOpen.getState()).isEqualTo(CircuitBreakerState.OPEN);

        // Поставить в очередь новое сообщение (без симуляции сбоя)
        boolean enqueued = messageOutputPort.sendUplink(
                AIRCRAFT_ID, "CLEARANCE", Map.of(), UplinkOrigin.COMPUTER_GENERATED);
        assertThat(enqueued).isTrue();

        Long messageId = jdbcTemplate.queryForObject(
                "SELECT id FROM outbound_messages ORDER BY id DESC LIMIT 1", Long.class);

        // Тик поллера — breaker OPEN, таймаут НЕ истёк → fail-fast, sообщение остаётся PENDING
        deliveryScheduler.pollPendingMessages();

        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbound_messages WHERE id = ?", Integer.class, messageId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM outbound_messages WHERE id = ?", String.class, messageId);

        assertThat(status)
                .as("При OPEN breaker сообщение остаётся в PENDING (fail-fast, без попытки доставки)")
                .isEqualTo("PENDING");
        assertThat(attempts)
                .as("При OPEN breaker attempts НЕ инкрементируется (это не сбой ЭТОГО сообщения)")
                .isZero();

        // Readiness также DOWN
        assertReadiness(503, "Readiness DOWN при принудительно открытом breaker");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Проверяет HTTP-статус /actuator/health/readiness.
     *
     * @param expectedStatus ожидаемый HTTP-статус (200 = UP, 503 = DOWN/OUT_OF_SERVICE)
     * @param description    описание для assertion
     */
    private void assertReadiness(int expectedStatus, String description) {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);
        assertThat(resp.getStatusCode().value())
                .as(description)
                .isEqualTo(expectedStatus);
    }
}
