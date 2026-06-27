package ru.protectinfotrans.eca.integration.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;
import ru.protectinfotrans.eca.integration.port.out.DeadLetterRepositoryPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P5-3: доменный HealthIndicator для критичных исходящих каналов модуля {@code integration}.
 *
 * <p>Входит в readiness-группу ({@code management.endpoint.health.group.readiness.include}),
 * чтобы Kubernetes readiness probe знала о деградации доставки ДО того, как просроченные
 * outbound-сообщения накопятся в очереди.
 *
 * <p><b>Логика:</b>
 * <ul>
 *   <li>{@code DOWN} — хотя бы один isходящий канал (UPLINK/GROUND) в состоянии {@code OPEN}:
 *       circuit breaker сработал на серии сбоев, доставка заблокирована fail-fast. Readiness pod
 *       переходит в DOWN → k8s прекращает отправлять трафик пода, пока канал не восстановится.</li>
 *   <li>{@code OUT_OF_SERVICE} — размер DLQ (записи в статусе {@code NEW}) превысил критичный
 *       порог ({@code app.health.dlq-critical-size}, default 100): оператор ещё не обработал
 *       накопившиеся сбои. Деградированное, но рабочее состояние.</li>
 *   <li>{@code UP} — все каналы {@code CLOSED}/{@code HALF_OPEN}, DLQ в норме.</li>
 * </ul>
 *
 * <p><b>Расположение в модуле:</b> класс намеренно помещён в {@code integration.application}
 * (внутри модуля {@code integration}) — может напрямую использовать внутренние порты
 * {@link CircuitBreakerRepositoryPort} и {@link DeadLetterRepositoryPort} без нарушения
 * Modulith-границ (никакой кросс-модульный доступ не происходит). Имя бина —
 * {@code integrationChannelsHealthIndicator} → component ID в health-эндпоинте:
 * {@code integrationChannels}.
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.groups">
 *      Spring Boot Health Groups</a>
 */
@Component
@Slf4j
public class IntegrationChannelsHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRepositoryPort circuitBreakerRepository;
    private final DeadLetterRepositoryPort deadLetterRepository;

    /**
     * Критичный размер DLQ (число записей в статусе NEW): при превышении readiness переходит
     * в OUT_OF_SERVICE. Настраивается без пересборки через переменную окружения / config-map.
     */
    @Value("${app.health.dlq-critical-size:100}")
    private long dlqCriticalSize;

    public IntegrationChannelsHealthIndicator(
            CircuitBreakerRepositoryPort circuitBreakerRepository,
            DeadLetterRepositoryPort deadLetterRepository) {
        this.circuitBreakerRepository = circuitBreakerRepository;
        this.deadLetterRepository = deadLetterRepository;
    }

    @Override
    public Health health() {
        try {
            return doCheck();
        } catch (Exception ex) {
            log.error("IntegrationChannelsHealthIndicator: ошибка при проверке состояния каналов", ex);
            return Health.unknown()
                    .withDetail("error", ex.getClass().getSimpleName() + ": " + ex.getMessage())
                    .build();
        }
    }

    private Health doCheck() {
        // --- Circuit breaker state for all channels ---
        List<ChannelCircuitBreaker> breakers = circuitBreakerRepository.findAll();
        Map<String, String> cbStates = new LinkedHashMap<>();
        boolean anyOpen = false;
        List<String> openChannels = new java.util.ArrayList<>();

        for (ChannelCircuitBreaker cb : breakers) {
            cbStates.put(cb.getChannel().name(), cb.getState().name());
            if (cb.getState() == CircuitBreakerState.OPEN) {
                anyOpen = true;
                openChannels.add(cb.getChannel().name());
            }
        }

        // --- DLQ size (NEW = waiting for operator action) ---
        long dlqSize = deadLetterRepository.countByStatus(DeadLetterStatus.NEW);

        // --- Aggregate status (circuit breaker failure is more severe than DLQ backlog) ---
        if (anyOpen) {
            log.warn("IntegrationChannels health: DOWN — open circuit breaker(s): {}", openChannels);
            return Health.down()
                    .withDetail("circuitBreakers", cbStates)
                    .withDetail("openChannels", openChannels)
                    .withDetail("dlqSize", dlqSize)
                    .withDetail("reason", "Outbound channel(s) unreachable: circuit breaker OPEN")
                    .build();
        }

        if (dlqSize > dlqCriticalSize) {
            log.warn("IntegrationChannels health: OUT_OF_SERVICE — DLQ size {} > threshold {}",
                    dlqSize, dlqCriticalSize);
            return Health.outOfService()
                    .withDetail("circuitBreakers", cbStates)
                    .withDetail("dlqSize", dlqSize)
                    .withDetail("dlqCriticalSize", dlqCriticalSize)
                    .withDetail("reason", "DLQ size exceeds critical threshold — operator action required")
                    .build();
        }

        return Health.up()
                .withDetail("circuitBreakers", cbStates.isEmpty() ? "none registered" : cbStates)
                .withDetail("dlqSize", dlqSize)
                .build();
    }
}
