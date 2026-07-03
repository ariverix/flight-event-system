package ru.protectinfotrans.eca;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.protectinfotrans.eca.cluster.ApplicationReadiness;
import ru.protectinfotrans.eca.cluster.LeaderElection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P2-3 (гигиена старта): unit-тесты гейтов {@link RetentionService#runRetention()}.
 *
 * <p>Retention никогда не должен трогать БД ({@link JdbcTemplate}), пока приложение не готово
 * ({@link ApplicationReadiness#isReady()} == false) или реплика не лидер
 * ({@link LeaderElection#isLeader()} == false) — иначе на старте DROP/DELETE могли бы уйти в схему
 * до её готовности или продублироваться на всех репликах. Оба гейта — функциональные интерфейсы,
 * подставляются лямбда-заглушками.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetentionService — гейты готовности/лидерства (P2-3/P6-1)")
class RetentionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private RetentionService retentionService(ApplicationReadiness readiness, LeaderElection leader) {
        return new RetentionService(leader, readiness, jdbcTemplate, new RetentionProperties(),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("приложение не готово (isReady=false) → БД не трогается, даже если лидер")
    void doesNothingWhenNotReady() {
        RetentionService service = retentionService(() -> false, () -> true);

        service.runRetention();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("готово, но не лидер (isLeader=false) → БД не трогается")
    void doesNothingWhenNotLeader() {
        RetentionService service = retentionService(() -> true, () -> false);

        service.runRetention();

        verifyNoInteractions(jdbcTemplate);
    }

    // Фаза 3 (defense-in-depth DDL): whitelist-guard имени партиции перед CREATE/DROP TABLE.
    @Test
    @DisplayName("assertSafePartitionName: валидное имя tracking_event_log_YYYY_MM проходит")
    void safePartitionNameAccepted() {
        RetentionService service = retentionService(() -> true, () -> true);

        assertThat(catchThrowable(() -> service.assertSafePartitionName("tracking_event_log_2026_07"))).isNull();
    }

    @Test
    @DisplayName("assertSafePartitionName: инъекция/произвольный идентификатор отвергается (IllegalArgumentException)")
    void unsafePartitionNameRejected() {
        RetentionService service = retentionService(() -> true, () -> true);

        for (String bad : new String[]{
                "users; DROP TABLE users",       // SQL-инъекция
                "tracking_event_log",            // без суффикса даты
                "tracking_event_log_2026_7",     // месяц не 2 цифры
                "other_table_2026_07",           // чужой префикс
                null}) {
            assertThatThrownBy(() -> service.assertSafePartitionName(bad))
                    .as("должно отвергнуть: %s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
