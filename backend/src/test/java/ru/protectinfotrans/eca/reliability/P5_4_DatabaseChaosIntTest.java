package ru.protectinfotrans.eca.reliability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5-4 Chaos: недоступность БД PostgreSQL — предсказуемая деградация без зависания + восстановление.
 *
 * <p><b>TDD (P5-4, CLAUDE.md):</b> тест написан первым — доказывает устойчивость конфигурации
 * пула соединений (HikariCP) и Spring Boot health indicator к потере БД.
 *
 * <p><b>НЕ использует BaseIntegrationTest</b> — запускает собственные изолированные
 * PostgreSQL-контейнеры (Testcontainers), не конкурирует с общей тестовой БД localhost:5432.
 *
 * <p><b>Что доказывает:</b>
 * <ol>
 *   <li><b>Fail-fast (не зависание):</b> после остановки контейнера попытка получить соединение
 *       завершается {@link SQLException} за ≤ {@code EXPECTED_FAILURE_TIMEOUT_MS} мс
 *       (HikariCP {@code connectionTimeout = 3000мс}), а не висит бесконечно.</li>
 *   <li><b>Health DOWN при недоступной БД:</b> {@link DataSourceHealthIndicator} с
 *       DataSource, указывающим на недоступный хост, возвращает {@link Status#DOWN}
 *       (аналог того, что делает Spring Boot readiness probe через db-индикатор).</li>
 *   <li><b>Полный lifecycle UP→DOWN→UP:</b> контейнер запущен → health UP → контейнер остановлен →
 *       соединение из пула инвалидировано → health DOWN → новый контейнер (БД "вернулась") →
 *       health UP. Доказывает восстановление.</li>
 * </ol>
 *
 * <p><b>Scope-граница:</b> настоящий replica-failover (переключение primary→replica с leader
 * election) — P6-1, не реализован. Этот тест покрывает одиночный узел.
 *
 * <p><b>HikariCP timeouts (прод-рекомендация, см. chaos-failover.md):</b>
 * <pre>
 *   connectionTimeout: 10000  (10с — не ждать вечно)
 *   validationTimeout: 5000   (5с)
 *   keepalive-time:    30000  (30с — обнаружение мёртвых соединений)
 * </pre>
 * В тестах используем меньшее значение (3000мс) для скорости.
 */
@Slf4j
@DisplayName("P5-4 Chaos: недоступность БД — fail-fast + health DOWN + recovery")
class P5_4_DatabaseChaosIntTest {

    private static final String PG_IMAGE = "postgres:16-alpine";
    private static final String DB_USER = "eca_user";
    private static final String DB_PASS = "eca_password";
    private static final String DB_NAME = "eca_chaos_test";

    /**
     * Максимальное ожидаемое время ошибки соединения (connectionTimeout + запас).
     * Hikari connectionTimeout = 3000мс → ожидаем завершение за ≤ 5с.
     */
    private static final long EXPECTED_FAILURE_TIMEOUT_MS = 5_000;

    /** Hikari connectionTimeout для хаос-тестов — короткий, чтобы не ждать в CI. */
    private static final int HIKARI_CONNECTION_TIMEOUT_MS = 3_000;

    // ========================================================================
    // Сценарий 1: fail-fast — остановка контейнера → соединение падает быстро
    // ========================================================================

    @Test
    @DisplayName("DB stopped: соединение из пула падает быстро (≤5с), не зависает")
    void dbContainerStopped_connectionFailsFastNotHanging() throws Exception {
        try (PostgreSQLContainer<?> pg = startContainer()) {
            HikariDataSource ds = buildDataSource(pg.getJdbcUrl(), HIKARI_CONNECTION_TIMEOUT_MS);
            try {
                // Убедиться, что соединение изначально работает
                try (Connection conn = ds.getConnection()) {
                    conn.createStatement().execute("SELECT 1");
                    log.info("P5-4 DB Chaos: соединение с контейнером установлено: {}", pg.getJdbcUrl());
                }

                // Остановить контейнер (БД недоступна)
                pg.stop();
                log.info("P5-4 DB Chaos: контейнер остановлен. Проверяем fail-fast...");

                // Выгнать все соединения из пула (они ссылаются на остановленный контейнер)
                ds.getHikariPoolMXBean().softEvictConnections();

                // Попытка получить соединение из пула — должна упасть быстро (≤ HIKARI_CONNECTION_TIMEOUT_MS)
                long start = System.currentTimeMillis();

                assertThatThrownBy(ds::getConnection)
                        .as("После остановки контейнера getConnection() должен бросить SQLException")
                        .isInstanceOf(SQLException.class);

                long elapsed = System.currentTimeMillis() - start;
                log.info("P5-4 DB Chaos: соединение упало за {}мс (ожидалось ≤{}мс)",
                        elapsed, EXPECTED_FAILURE_TIMEOUT_MS);

                assertThat(elapsed)
                        .as("Отказ соединения должен произойти быстро (HikariCP connectionTimeout), не зависать")
                        .isLessThan(EXPECTED_FAILURE_TIMEOUT_MS);

            } finally {
                ds.close();
            }
        }
    }

    // ========================================================================
    // Сценарий 2: health DOWN при недоступной БД
    // ========================================================================

    @Test
    @DisplayName("DB недоступна: DataSourceHealthIndicator возвращает Status.DOWN за ≤5с")
    void dbUnreachable_healthIndicatorReportsDown_fastNotHanging() {
        // DataSource, указывающий на порт без слушателя (никакого PostgreSQL там нет).
        // Аналог того, что делает Spring Boot db-healthIndicator из readiness группы P5-3.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:29999/nonexistent_db");
        config.setUsername(DB_USER);
        config.setPassword(DB_PASS);
        config.setConnectionTimeout(HIKARI_CONNECTION_TIMEOUT_MS);
        config.setInitializationFailTimeout(-1); // не падать при старте пула
        config.setPoolName("chaos-health-down-pool");

        try (HikariDataSource ds = new HikariDataSource(config)) {
            DataSourceHealthIndicator indicator = new DataSourceHealthIndicator(ds, "SELECT 1");

            long start = System.currentTimeMillis();
            Health health = indicator.health();
            long elapsed = System.currentTimeMillis() - start;

            log.info("P5-4 DB Chaos: DataSourceHealthIndicator вернул {} за {}мс",
                    health.getStatus(), elapsed);

            assertThat(health.getStatus())
                    .as("Health indicator с недоступной БД должен вернуть Status.DOWN")
                    .isEqualTo(Status.DOWN);

            assertThat(elapsed)
                    .as("Health check должен завершиться быстро (≤ EXPECTED_FAILURE_TIMEOUT_MS), не зависать")
                    .isLessThan(EXPECTED_FAILURE_TIMEOUT_MS);

            assertThat(health.getDetails())
                    .as("Health indicator должен содержать информацию об ошибке в details")
                    .containsKey("error");
        }
    }

    // ========================================================================
    // Сценарий 3: полный lifecycle UP→DOWN→UP (recovery)
    // ========================================================================

    @Test
    @DisplayName("Full lifecycle: DB UP → DOWN (контейнер остановлен) → UP (новый контейнер)")
    void dbLifecycle_upThenDownThenRecoveredWithNewContainer() throws Exception {
        // ── Фаза 1: БД доступна (контейнер запущен) ─────────────────────────
        String phase1JdbcUrl;
        try (PostgreSQLContainer<?> pg1 = startContainer()) {
            phase1JdbcUrl = pg1.getJdbcUrl();
            try (HikariDataSource ds1 = buildDataSource(phase1JdbcUrl, HIKARI_CONNECTION_TIMEOUT_MS)) {
                DataSourceHealthIndicator ind1 = new DataSourceHealthIndicator(ds1, "SELECT 1");

                Health health1 = ind1.health();
                log.info("P5-4 DB Chaos Phase1: health = {}", health1.getStatus());

                assertThat(health1.getStatus())
                        .as("Фаза 1 (контейнер запущен): health должен быть UP")
                        .isEqualTo(Status.UP);

                // ── Фаза 2: БД недоступна (контейнер остановлен) ─────────────────
                pg1.stop();
                ds1.getHikariPoolMXBean().softEvictConnections();

                long start2 = System.currentTimeMillis();
                Health health2 = ind1.health();
                long elapsed2 = System.currentTimeMillis() - start2;

                log.info("P5-4 DB Chaos Phase2: health = {} за {}мс", health2.getStatus(), elapsed2);

                assertThat(health2.getStatus())
                        .as("Фаза 2 (контейнер остановлен): health должен быть DOWN")
                        .isEqualTo(Status.DOWN);
                assertThat(elapsed2)
                        .as("Деградация должна быть обнаружена быстро, не зависать")
                        .isLessThan(EXPECTED_FAILURE_TIMEOUT_MS);
            }
        }

        // ── Фаза 3: БД «вернулась» (новый контейнер — PostgreSQL поднялся снова) ─
        try (PostgreSQLContainer<?> pg2 = startContainer()) {
            try (HikariDataSource ds2 = buildDataSource(pg2.getJdbcUrl(), 5_000)) {
                DataSourceHealthIndicator ind2 = new DataSourceHealthIndicator(ds2, "SELECT 1");

                Health health3 = ind2.health();
                log.info("P5-4 DB Chaos Phase3 (recovery): health = {}", health3.getStatus());

                assertThat(health3.getStatus())
                        .as("Фаза 3 (новый контейнер, БД вернулась): health должен быть UP снова")
                        .isEqualTo(Status.UP);
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Запускает новый PostgreSQL 16 контейнер с тестовыми credentials.
     * Возвращает запущенный контейнер — вызывающий отвечает за его остановку.
     */
    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> startContainer() {
        PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(PG_IMAGE)
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASS);
        pg.start();
        log.info("P5-4 DB Chaos: PostgreSQL контейнер запущен: {}", pg.getJdbcUrl());
        return pg;
    }

    /**
     * Создаёт HikariCP DataSource с коротким {@code connectionTimeout} для хаос-тестов.
     *
     * @param jdbcUrl           JDBC URL (из Testcontainers)
     * @param connectionTimeout HikariCP connectionTimeout в миллисекундах
     */
    private static HikariDataSource buildDataSource(String jdbcUrl, int connectionTimeout) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(DB_USER);
        cfg.setPassword(DB_PASS);
        cfg.setConnectionTimeout(connectionTimeout);
        cfg.setInitializationFailTimeout(-1);      // не падать если пул не инициализирован
        cfg.setPoolName("chaos-db-test-pool");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);                     // не держать idle-соединения
        return new HikariDataSource(cfg);
    }
}
