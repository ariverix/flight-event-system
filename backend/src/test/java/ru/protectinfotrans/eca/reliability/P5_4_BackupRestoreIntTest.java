package ru.protectinfotrans.eca.reliability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-4: Интеграционный тест восстановления БД (pg_dump / pg_restore) через Testcontainers.
 *
 * <p><b>TDD (P5-4, CLAUDE.md):</b> тест написан первым — проверяет сценарий восстановления
 * из логического бэкапа до реализации эксплуатационного процесса.
 *
 * <p><b>Что доказывает:</b>
 * <ol>
 *   <li>Поднимает SOURCE-контейнер PostgreSQL 16, накатывает Flyway V1–V35, вставляет
 *       доменные данные (последовательность VP-BQR/SU1234, сообщения ACARS, инстанс).</li>
 *   <li>Выполняет {@code pg_dump --format=custom --no-owner} внутри SOURCE-контейнера
 *       ({@code execInContainer}) → файл {@code /tmp/eca_backup.dump}.</li>
 *   <li>Копирует дамп из SOURCE-контейнера на хост ({@code copyFileFromContainer}),
 *       затем в TARGET-контейнер ({@code copyFileToContainer}).</li>
 *   <li>Выполняет {@code pg_restore --no-owner} внутри TARGET-контейнера.</li>
 *   <li>Проверяет через JDBC:
 *       <ul>
 *         <li>Количество строк в {@code sequences}, {@code messages}, {@code steps}
 *             совпадает с SOURCE.</li>
 *         <li>Конкретная запись {@code sequences.name = 'P5-4 Restore Test'} присутствует.</li>
 *         <li>Конкретное сообщение {@code aircraft_id = 'VP-BQR'} присутствует.</li>
 *         <li>{@code flyway_schema_history} содержит ровно 35 успешных миграций
 *             (V35 — последняя).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>НЕ использует BaseIntegrationTest</b> (тот работает на фиксированном localhost:5432) —
 * поднимает собственные изолированные контейнеры, не конкурирует с общей тестовой БД.
 *
 * <p><b>Бинари pg_dump/pg_restore</b> выполняются ВНУТРИ контейнера {@code postgres:16-alpine}
 * (они всегда доступны в образе); не требует наличия postgresql-client на хосте.
 *
 * <p><b>Scope-граница:</b> настоящий replica-failover (P6-1) — не реализован.
 * Тест покрывает логический бэкап одиночного узла PostgreSQL.
 */
@Slf4j
@DisplayName("P5-4: pg_dump/pg_restore — целостность данных после восстановления")
class P5_4_BackupRestoreIntTest {

    /** Версия PostgreSQL — та же, что в прод-окружении (CLAUDE.md: PostgreSQL 16). */
    private static final String PG_IMAGE = "postgres:16-alpine";

    /** DB-credentials (те же, что у BaseIntegrationTest для единообразия). */
    private static final String DB_USER = "eca_user";
    private static final String DB_PASS = "eca_password";
    private static final String SOURCE_DB = "eca_backup_src";
    private static final String TARGET_DB = "eca_backup_tgt";

    /** Демо-борт (стиль EcaParityScenarioIntTest). */
    private static final String AIRCRAFT_ID = "VP-BQR";
    private static final String FLIGHT_NUMBER = "SU1234";
    private static final String TEST_SEQUENCE_NAME = "P5-4 Restore Test";

    private static PostgreSQLContainer<?> sourceContainer;
    private static PostgreSQLContainer<?> targetContainer;

    private static HikariDataSource sourceDatasource;
    private static HikariDataSource targetDatasource;

    private static JdbcTemplate sourceJdbc;
    private static JdbcTemplate targetJdbc;

    /** Временный файл с дампом на хосте (bridge между контейнерами). */
    private static Path localDumpFile;

    // ========================================================================
    // Setup / Teardown
    // ========================================================================

    @BeforeAll
    static void setUpContainersAndData() throws IOException, InterruptedException {
        log.info("P5-4: Запуск SOURCE и TARGET PostgreSQL контейнеров...");

        // --- SOURCE контейнер -----------------------------------------------
        sourceContainer = new PostgreSQLContainer<>(PG_IMAGE)
                .withDatabaseName(SOURCE_DB)
                .withUsername(DB_USER)
                .withPassword(DB_PASS);
        sourceContainer.start();

        sourceDatasource = buildDataSource(
                sourceContainer.getJdbcUrl(), DB_USER, DB_PASS, "source-pool");
        sourceJdbc = new JdbcTemplate(sourceDatasource);

        // Накатить все Flyway-миграции V1–V35 в SOURCE
        migrateWithFlyway(sourceDatasource);
        log.info("P5-4: Flyway migrate завершён на SOURCE.");

        // Вставить доменные тестовые данные
        insertTestData(sourceJdbc);
        log.info("P5-4: Тестовые данные вставлены в SOURCE.");

        // --- Выполнить pg_dump внутри SOURCE-контейнера ---------------------
        String dumpCmd = String.format(
                "PGPASSWORD=%s pg_dump -h localhost -U %s --format=custom --no-owner --no-privileges -f /tmp/eca_backup.dump %s",
                DB_PASS, DB_USER, SOURCE_DB);

        ExecResult dumpResult = sourceContainer.execInContainer("sh", "-c", dumpCmd);

        assertThat(dumpResult.getExitCode())
                .as("pg_dump должен завершиться с кодом 0. stderr:\n" + dumpResult.getStderr())
                .isZero();

        log.info("P5-4: pg_dump выполнен. stderr (verbose):\n{}", dumpResult.getStderr());

        // --- Скопировать дамп из SOURCE-контейнера на хост ------------------
        localDumpFile = Files.createTempFile("eca-p5-4-restore-", ".dump");
        sourceContainer.copyFileFromContainer(
                "/tmp/eca_backup.dump",
                (InputStream is) -> {
                    Files.copy(is, localDumpFile, StandardCopyOption.REPLACE_EXISTING);
                    return null;
                });

        long dumpBytes = Files.size(localDumpFile);
        assertThat(dumpBytes)
                .as("Файл дампа не должен быть пустым")
                .isGreaterThan(0);
        log.info("P5-4: Дамп скопирован на хост: {} ({} байт)", localDumpFile, dumpBytes);

        // --- TARGET контейнер -----------------------------------------------
        targetContainer = new PostgreSQLContainer<>(PG_IMAGE)
                .withDatabaseName(TARGET_DB)
                .withUsername(DB_USER)
                .withPassword(DB_PASS);
        targetContainer.start();

        // Скопировать дамп с хоста в TARGET-контейнер
        targetContainer.copyFileToContainer(
                MountableFile.forHostPath(localDumpFile.toString()),
                "/tmp/eca_restore.dump");

        // --- Выполнить pg_restore внутри TARGET-контейнера ------------------
        String restoreCmd = String.format(
                "PGPASSWORD=%s pg_restore -h localhost -U %s --no-owner --no-privileges --exit-on-error -d %s /tmp/eca_restore.dump",
                DB_PASS, DB_USER, TARGET_DB);

        ExecResult restoreResult = targetContainer.execInContainer("sh", "-c", restoreCmd);

        assertThat(restoreResult.getExitCode())
                .as("pg_restore должен завершиться с кодом 0. stderr:\n" + restoreResult.getStderr())
                .isZero();
        log.info("P5-4: pg_restore выполнен. stderr:\n{}", restoreResult.getStderr());

        // --- DataSource для проверки TARGET ----------------------------------
        targetDatasource = buildDataSource(
                targetContainer.getJdbcUrl(), DB_USER, DB_PASS, "target-pool");
        targetJdbc = new JdbcTemplate(targetDatasource);
    }

    @AfterAll
    static void tearDown() {
        if (sourceDatasource != null) sourceDatasource.close();
        if (targetDatasource != null) targetDatasource.close();
        if (sourceContainer != null) sourceContainer.stop();
        if (targetContainer != null) targetContainer.stop();
        if (localDumpFile != null) {
            try { Files.deleteIfExists(localDumpFile); } catch (IOException ignored) {}
        }
    }

    // ========================================================================
    // Тесты верификации целостности
    // ========================================================================

    @Test
    @DisplayName("Количество строк в sequences совпадает SOURCE ↔ TARGET")
    void sequencesRowCountMatchesAfterRestore() {
        long sourceCount = count(sourceJdbc, "sequences");
        long targetCount = count(targetJdbc, "sequences");

        assertThat(targetCount)
                .as("TARGET.sequences должна содержать столько же строк, сколько SOURCE")
                .isEqualTo(sourceCount);
        assertThat(sourceCount).as("В sequences должны быть строки (миграции + тестовые)").isPositive();
    }

    @Test
    @DisplayName("Количество строк в steps совпадает SOURCE ↔ TARGET")
    void stepsRowCountMatchesAfterRestore() {
        long sourceCount = count(sourceJdbc, "steps");
        long targetCount = count(targetJdbc, "steps");

        assertThat(targetCount)
                .as("TARGET.steps должна содержать столько же строк, сколько SOURCE")
                .isEqualTo(sourceCount);
    }

    @Test
    @DisplayName("Количество строк в messages совпадает SOURCE ↔ TARGET")
    void messagesRowCountMatchesAfterRestore() {
        long sourceCount = count(sourceJdbc, "messages");
        long targetCount = count(targetJdbc, "messages");

        assertThat(targetCount)
                .as("TARGET.messages должна содержать столько же строк, сколько SOURCE")
                .isEqualTo(sourceCount);
        assertThat(sourceCount).as("В messages должны быть строки (тестовые данные)").isPositive();
    }

    @Test
    @DisplayName("Конкретная тестовая последовательность VP-BQR/SU1234 присутствует в TARGET")
    void specificTestSequenceExistsInTarget() {
        Long sourceId = sourceJdbc.queryForObject(
                "SELECT id FROM sequences WHERE name = ?", Long.class, TEST_SEQUENCE_NAME);

        Long targetId = targetJdbc.queryForObject(
                "SELECT id FROM sequences WHERE name = ?", Long.class, TEST_SEQUENCE_NAME);

        assertThat(targetId)
                .as("Тестовая последовательность '%s' должна быть в TARGET", TEST_SEQUENCE_NAME)
                .isNotNull()
                .isEqualTo(sourceId);

        // Проверить описание
        String targetDescription = targetJdbc.queryForObject(
                "SELECT description FROM sequences WHERE name = ?", String.class, TEST_SEQUENCE_NAME);
        assertThat(targetDescription)
                .as("Описание последовательности должно восстановиться точно")
                .contains(AIRCRAFT_ID)
                .contains(FLIGHT_NUMBER);
    }

    @Test
    @DisplayName("Тестовые сообщения для борта VP-BQR присутствуют в TARGET")
    void acarsMessagesForAircraftRestoredInTarget() {
        Long sourceCount = sourceJdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE aircraft_id = ?", Long.class, AIRCRAFT_ID);
        Long targetCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE aircraft_id = ?", Long.class, AIRCRAFT_ID);

        assertThat(targetCount)
                .as("Сообщения для борта %s должны быть восстановлены в TARGET", AIRCRAFT_ID)
                .isEqualTo(sourceCount)
                .isPositive();
    }

    @Test
    @DisplayName("Инстанс выполнения для VP-BQR присутствует в TARGET")
    void executionInstanceRestoredInTarget() {
        Long sourceCount = sourceJdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_instances WHERE aircraft_id = ?", Long.class, AIRCRAFT_ID);
        Long targetCount = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_instances WHERE aircraft_id = ?", Long.class, AIRCRAFT_ID);

        assertThat(targetCount)
                .as("Инстанс выполнения для борта %s должен быть восстановлен", AIRCRAFT_ID)
                .isEqualTo(sourceCount)
                .isPositive();
    }

    @Test
    @DisplayName("Flyway schema_history: V35 — последняя миграция (35 успешных миграций в TARGET)")
    void flywaySchemaHistoryReflectsV35AfterRestore() {
        // Проверяем количество успешных миграций — должно быть 35 (V1..V35)
        Long migrationsInSource = sourceJdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Long.class);
        Long migrationsInTarget = targetJdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Long.class);

        assertThat(migrationsInTarget)
                .as("TARGET: flyway_schema_history должна содержать столько же успешных миграций, сколько SOURCE")
                .isEqualTo(migrationsInSource);
        assertThat(migrationsInSource)
                .as("Должно быть 35 успешных миграций (V1..V35)")
                .isEqualTo(35L);

        // Последняя миграция по installed_rank — версия '35'
        String lastVersionInTarget = targetJdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(lastVersionInTarget)
                .as("Последняя миграция в TARGET — V35")
                .isEqualTo("35");
    }

    @Test
    @DisplayName("Количество строк в users совпадает SOURCE ↔ TARGET (включая admin из V8)")
    void usersRestoredInTarget() {
        long sourceCount = count(sourceJdbc, "users");
        long targetCount = count(targetJdbc, "users");

        assertThat(targetCount)
                .as("Таблица users должна быть идентична после восстановления")
                .isEqualTo(sourceCount)
                .isPositive(); // admin создан в V8
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static void migrateWithFlyway(HikariDataSource ds) {
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .validateOnMigrate(false)
                .outOfOrder(true)
                .cleanDisabled(false)
                .load();
        flyway.migrate();
    }

    /**
     * Вставляет тестовые доменные данные:
     * - 1 последовательность для борта VP-BQR/SU1234
     * - 2 ACARS-сообщения для VP-BQR (входящий DOWNLINK + исходящий UPLINK)
     * - 1 инстанс выполнения (COMPLETED)
     */
    private static void insertTestData(JdbcTemplate jdbc) {
        // Последовательность
        jdbc.update("""
                INSERT INTO sequences (name, description, status, start_criteria, stop_criteria, created_at, updated_at, created_by)
                VALUES (?, ?, 'ACTIVE',
                        '{"type":"FLIGHT_STAGE","operator":"EQUALS","targetStage":"OFF"}',
                        '{"type":"FLIGHT_STAGE","operator":"GREATER_OR_EQUAL","targetStage":"ON"}',
                        NOW(), NOW(), 1)
                """,
                TEST_SEQUENCE_NAME,
                "Тест P5-4: backup/restore для борта " + AIRCRAFT_ID + " рейс " + FLIGHT_NUMBER);

        Long seqId = jdbc.queryForObject(
                "SELECT id FROM sequences WHERE name = ?", Long.class, TEST_SEQUENCE_NAME);

        // Шаг последовательности
        jdbc.update("""
                INSERT INTO steps (sequence_id, order_index, name, step_type, config,
                                   on_success_action, on_success_notify, on_failure_action, on_failure_notify)
                VALUES (?, 1, 'P5-4 Test Step', 'ACTION',
                        '{"actionType":"SEND_UPLINK","templateName":"POSITION_REQUEST","params":{}}',
                        'CONTINUE', false, 'END', false)
                """, seqId);

        // Входящее ACARS-сообщение (DOWNLINK position report)
        jdbc.update("""
                INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content,
                                      metadata_json, received_at)
                VALUES ('DOWNLINK', 'POSITION_REPORT', ?, ?, 'AN/VP-BQR POS/55.75 37.62 ALT/10000 FL/330',
                        '{"source":"ACARS","format":"ARINC_618"}', NOW())
                """, AIRCRAFT_ID, FLIGHT_NUMBER);

        // Исходящее сообщение (UPLINK)
        jdbc.update("""
                INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, content,
                                      metadata_json, received_at)
                VALUES ('UPLINK', 'POSITION_REQUEST', ?, ?, 'AN/VP-BQR REQUEST POSITION REPORT',
                        '{"source":"ECA_ENGINE"}', NOW())
                """, AIRCRAFT_ID, FLIGHT_NUMBER);

        // Инстанс выполнения
        jdbc.update("""
                INSERT INTO execution_instances (sequence_id, aircraft_id, flight_number, status,
                                                  current_step_index, context, started_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', 1, '{"waitStartedAt":null}', NOW() - INTERVAL '5 minutes', NOW())
                """, seqId, AIRCRAFT_ID, FLIGHT_NUMBER);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count != null ? count : 0L;
    }

    private static HikariDataSource buildDataSource(String jdbcUrl, String user, String pass, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setPoolName(poolName);
        cfg.setConnectionTimeout(30_000);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        return new HikariDataSource(cfg);
    }
}
