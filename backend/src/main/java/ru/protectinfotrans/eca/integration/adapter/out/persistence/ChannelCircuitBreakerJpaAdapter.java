package ru.protectinfotrans.eca.integration.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.integration.domain.ChannelCircuitBreaker;
import ru.protectinfotrans.eca.integration.domain.CircuitBreakerState;
import ru.protectinfotrans.eca.integration.domain.OutboundMessageType;
import ru.protectinfotrans.eca.integration.port.out.CircuitBreakerRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChannelCircuitBreakerJpaAdapter implements CircuitBreakerRepositoryPort {

    private final ChannelCircuitBreakerJpaRepository jpaRepository;

    /**
     * Lazy init в {@code REQUIRES_NEW}: первое обращение к каналу создаёт CLOSED-строку — той же
     * короткой собственной транзакцией, что {@code OutboundMessageJpaAdapter#claimPending}
     * (P2-3), чтобы конкурентный insert той же {@code @Id} (PK по {@code channel}) под гонкой не
     * портил внешнюю, более длинную транзакцию вызывающего (constraint violation на flush не
     * должен откатывать весь тик поллера).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChannelCircuitBreaker getOrCreate(OutboundMessageType channel) {
        return jpaRepository.findById(channel).orElseGet(() -> {
            try {
                return jpaRepository.save(ChannelCircuitBreaker.builder()
                        .channel(channel)
                        .state(CircuitBreakerState.CLOSED)
                        .consecutiveFailures(0)
                        .build());
            } catch (org.springframework.dao.DataIntegrityViolationException raceLost) {
                // конкурентный поток выиграл гонку lazy-init — запись уже есть, читаем её
                return jpaRepository.findById(channel).orElseThrow(() -> raceLost);
            }
        });
    }

    /**
     * P2-6: явная {@code @Transactional(REQUIRES_NEW)} — по тому же принципу, что
     * {@link #getOrCreate}/{@link #claimHalfOpenProbe} выше и {@code OutboundMessageJpaAdapter
     * #claimPending} (P2-3): {@code @Modifying} bulk JPQL UPDATE требует активной транзакции
     * вокруг {@code EntityManager.executeUpdate}, а {@code SimpleJpaRepository} оборачивает
     * транзакцией только базовый CRUD, не производные {@code @Query}-методы. Метод вызывается и
     * из {@code OutboundMessageDeliveryScheduler#deliverOne} (где уже есть REQUIRES_NEW-транзакция
     * — в этом случае метод просто присоединяется к ней, propagation REQUIRES_NEW тут избыточен,
     * но не вреден), и напрямую из IT-тестов без какой-либо транзакции — собственная транзакция
     * делает адаптер самодостаточным независимо от вызывающей стороны.
     *
     * <p><b>{@code getOrCreate} перед UPDATE:</b> {@code recordSuccess}/{@code recordFailure} —
     * чистый bulk {@code UPDATE ... WHERE channel = :channel} (см. {@code
     * ChannelCircuitBreakerJpaRepository}) — без строки для канала это безмолвный no-op (0 строк
     * обновлено). В реальном пути {@code deliverOne} строка гарантированно уже создана
     * предшествующим вызовом {@link #getOrCreate}, но вызов в лоб (напрямую, например из теста, до
     * первого {@code deliverOne} для канала) иначе тихо терял бы факт сбоя/успеха — гарантируем
     * lazy-init здесь же, чтобы адаптер был корректен независимо от порядка вызовов.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(OutboundMessageType channel) {
        getOrCreate(channel);
        jpaRepository.recordSuccess(channel);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OutboundMessageType channel, boolean shouldOpen, int newConsecutiveFailures,
                               LocalDateTime openedAt) {
        getOrCreate(channel);
        jpaRepository.recordFailure(channel, shouldOpen, newConsecutiveFailures, openedAt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimHalfOpenProbe(OutboundMessageType channel) {
        return jpaRepository.claimHalfOpenProbe(channel) == 1;
    }

    /**
     * P5-3: read-only список всех известных circuit breaker'ов для health readiness-индикатора.
     * Не создаёт записи, в отличие от {@link #getOrCreate} — используется исключительно
     * для диагностики состояния каналов в {@code IntegrationChannelsHealthIndicator}.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ChannelCircuitBreaker> findAll() {
        return jpaRepository.findAll();
    }
}
