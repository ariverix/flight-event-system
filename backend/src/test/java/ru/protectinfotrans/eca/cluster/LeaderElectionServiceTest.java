package ru.protectinfotrans.eca.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P2-3 (гигиена старта): unit-тест гейта {@link LeaderElectionService#heartbeat()}.
 *
 * <p>{@code heartbeat} продлевает аренду лидерства, но лидерство впервые захватывается ТОЛЬКО в
 * {@code acquireOnStartup} на {@code ApplicationReadyEvent}. До этого события собственный флаг
 * {@code applicationReady} == false, и {@code heartbeat} обязан быть no-op — не обращаться к БД
 * (иначе на старте, до готовности схемы или во время межтестового Flyway-clean, тик шумел бы
 * ошибками). Проверяется через пакетный конструктор с мокнутым {@link JdbcTemplate}: без вызова
 * {@code acquireOnStartup} heartbeat не трогает БД.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaderElectionService — гейт готовности heartbeat (P2-3)")
class LeaderElectionServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private LeaderElectionService service() {
        // пакетный тестовый конструктор: изолированный lock_name, чтобы не пересекаться с
        // продакшн-слотом реального бина.
        return new LeaderElectionService(jdbc, "test-lock-p2-3", "holder-test", Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("heartbeat до ApplicationReadyEvent → БД не трогается, лидером не становится")
    void heartbeatBeforeReadyIsNoOp() {
        LeaderElectionService service = service();

        service.heartbeat();

        verifyNoInteractions(jdbc);
        assertThat(service.isLeader()).isFalse();
    }
}
