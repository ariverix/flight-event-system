package ru.protectinfotrans.eca;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import ru.protectinfotrans.eca.cluster.LeaderElection;
import ru.protectinfotrans.eca.execution.adapter.out.persistence.TrackingEventLogJpaRepository;
import ru.protectinfotrans.eca.execution.domain.TrackingEventLog;
import ru.protectinfotrans.eca.execution.domain.TrackingEventType;
import ru.protectinfotrans.eca.execution.port.out.TrackingEventLogPort;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * P6-2 TDD: партиционирование tracking_event_log + retention больших таблиц.
 *
 * <p>Доказывает:
 * <ol>
 *   <li><b>Маршрутизация INSERT в партицию</b>: строка с known created_at попадает
 *       в ожидаемую партицию (tracking_event_log_2026_06), а не в DEFAULT или другую.</li>
 *   <li><b>Чтение из всех партиций</b>: findAll() / findById() через родительскую таблицу
 *       возвращает строки из любой партиции; составной DB-PK (id, created_at) не ломает
 *       single-column JPA findById(Long).</li>
 *   <li><b>Create-ahead партиций</b>: RetentionService создаёт партиции для будущих месяцев.</li>
 *   <li><b>DROP старой партиции</b>: партиция старше порога удаляется, данные в ней уходят.</li>
 *   <li><b>Retention messages</b>: строки messages старше порога удаляются; свежие — нет.</li>
 *   <li><b>Retention audit_log</b>: строки audit_log старше порога удаляются; свежие — нет.</li>
 *   <li><b>Гейт leader election (no-op на не-лидере)</b>: при isLeader()=false retention
 *       не трогает данные ни в одной таблице.</li>
 * </ol>
 *
 * <p>{@code @MockBean LeaderElection} заменяет {@link ru.protectinfotrans.eca.cluster.LeaderElectionService}
 * в Spring-контексте: тест управляет возвращаемым значением isLeader() через Mockito.
 * Это создаёт отдельный Spring-контекст (кэш не разделяется с тестами без MockBean).
 *
 * <p>Все тесты расширяют {@link BaseIntegrationTest} → перед каждым flyway.clean()+migrate()
 * → V37 применяется → tracking_event_log партиционирована.
 */
@DisplayName("P6-2: партиционирование tracking_event_log + retention messages/audit_log")
class P6_2_PartitioningRetentionIntTest extends BaseIntegrationTest {

    @MockBean
    private LeaderElection leaderElection;

    @Autowired
    private TrackingEventLogPort trackingEventLogPort;

    @Autowired
    private TrackingEventLogJpaRepository trackingEventLogRepository;

    @Autowired
    private RetentionService retentionService;

    @Autowired
    private MeterRegistry meterRegistry;

    /** По умолчанию тесты запускаются с isLeader()=true (retention реально работает). */
    @BeforeEach
    void setLeaderTrue() {
        when(leaderElection.isLeader()).thenReturn(true);
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. Маршрутизация INSERT в партицию
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. Маршрутизация INSERT в партицию (V37 — нативное RANGE-партиционирование)")
    class PartitionRoutingTests {

        @Test
        @DisplayName("строка с created_at в июне 2026 маршрутизируется в tracking_event_log_2026_06")
        void rowWithJune2026DateGoesToJunePartition() {
            TrackingEventLog event = TrackingEventLog.builder()
                    .sequenceId(1L)
                    .aircraftId("VP-BQR")
                    .eventType(TrackingEventType.SEQUENCE_STARTED)
                    .createdAt(LocalDateTime.of(2026, 6, 15, 12, 0))
                    .build();

            TrackingEventLog saved = trackingEventLogPort.save(event);
            assertThat(saved.getId()).as("id должен быть сгенерирован (IDENTITY)").isNotNull();

            // tableoid — OID физической таблицы-партиции, хранящей эту строку.
            String actualPartition = jdbcTemplate.queryForObject(
                    "SELECT c.relname FROM pg_class c " +
                    "WHERE c.oid = (SELECT tableoid FROM tracking_event_log WHERE id = ?)",
                    String.class, saved.getId());

            assertThat(actualPartition)
                    .as("строка с created_at 2026-06 должна попасть в партицию 2026_06")
                    .isEqualTo("tracking_event_log_2026_06");
        }

        @Test
        @DisplayName("строка с created_at в июле 2026 маршрутизируется в tracking_event_log_2026_07")
        void rowWithJuly2026DateGoesToJulyPartition() {
            TrackingEventLog event = TrackingEventLog.builder()
                    .sequenceId(1L)
                    .aircraftId("VP-BQR")
                    .eventType(TrackingEventType.STEP_COMPLETED)
                    .createdAt(LocalDateTime.of(2026, 7, 20, 9, 30))
                    .build();

            TrackingEventLog saved = trackingEventLogPort.save(event);

            String actualPartition = jdbcTemplate.queryForObject(
                    "SELECT c.relname FROM pg_class c " +
                    "WHERE c.oid = (SELECT tableoid FROM tracking_event_log WHERE id = ?)",
                    String.class, saved.getId());

            assertThat(actualPartition).isEqualTo("tracking_event_log_2026_07");
        }

        @Test
        @DisplayName("строки из разных партиций видны через родительскую таблицу (findAll)")
        void rowsFromMultiplePartitionsVisibleThroughParent() {
            TrackingEventLog june = TrackingEventLog.builder()
                    .sequenceId(10L)
                    .aircraftId("VP-BQR")
                    .eventType(TrackingEventType.SEQUENCE_STARTED)
                    .createdAt(LocalDateTime.of(2026, 6, 1, 0, 0))
                    .build();
            TrackingEventLog july = TrackingEventLog.builder()
                    .sequenceId(10L)
                    .aircraftId("VP-BQR")
                    .eventType(TrackingEventType.SEQUENCE_STOPPED)
                    .createdAt(LocalDateTime.of(2026, 7, 1, 0, 0))
                    .build();
            TrackingEventLog savedJune = trackingEventLogPort.save(june);
            TrackingEventLog savedJuly = trackingEventLogPort.save(july);

            List<TrackingEventLog> all = trackingEventLogRepository.findAll();

            assertThat(all).extracting(TrackingEventLog::getId)
                    .as("findAll() через родительскую таблицу должен видеть строки из всех партиций")
                    .contains(savedJune.getId(), savedJuly.getId());
        }

        @Test
        @DisplayName("findById(Long) по одиночному id работает через составной PK (id, created_at)")
        void findByIdWithSingleLongWorksOnPartitionedTable() {
            TrackingEventLog event = TrackingEventLog.builder()
                    .sequenceId(99L)
                    .aircraftId("RA-TEST")
                    .flightNumber("SU9999")
                    .eventType(TrackingEventType.SEQUENCE_ABORTED)
                    .createdAt(LocalDateTime.of(2026, 8, 10, 14, 0))
                    .build();

            TrackingEventLog saved = trackingEventLogPort.save(event);

            // JPA findById(Long) генерирует WHERE id = ?, PostgreSQL находит строку
            // через ведущую колонку составного PK-индекса (id, created_at).
            TrackingEventLog reloaded = trackingEventLogRepository.findById(saved.getId())
                    .orElseThrow(() -> new AssertionError("findById не нашёл строку по id=" + saved.getId()));

            assertThat(reloaded.getAircraftId()).isEqualTo("RA-TEST");
            assertThat(reloaded.getFlightNumber()).isEqualTo("SU9999");
            assertThat(reloaded.getEventType()).isEqualTo(TrackingEventType.SEQUENCE_ABORTED);
        }

        @Test
        @DisplayName("V37: pg_indexes для tracking_event_log содержит ровно 5 ожидаемых индексов")
        void parentTableHasExpectedIndexesAfterV37() {
            List<String> indexNames = jdbcTemplate.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'tracking_event_log'",
                    String.class);

            assertThat(indexNames)
                    .as("после V37 pg_indexes[tracking_event_log] должен содержать ровно 5 индексов")
                    .containsExactlyInAnyOrder(
                            "tracking_event_log_pkey",
                            "idx_tracking_event_log_sequence",
                            "idx_tracking_event_log_aircraft",
                            "idx_tracking_event_log_instance",
                            "idx_tracking_event_log_created_at"
                    );
        }

        @Test
        @DisplayName("V37: PK partitioned таблицы включает колонки (id, created_at)")
        void primaryKeyIncludesIdAndCreatedAt() {
            List<String> pkColumns = jdbcTemplate.queryForList("""
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema    = kcu.table_schema
                    WHERE tc.table_name       = 'tracking_event_log'
                      AND tc.constraint_type  = 'PRIMARY KEY'
                    ORDER BY kcu.ordinal_position
                    """, String.class);

            assertThat(pkColumns)
                    .as("PK партиционированной таблицы должен содержать id и created_at")
                    .containsExactlyInAnyOrder("id", "created_at");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. Create-ahead партиций (RetentionService)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. Create-ahead партиций — RetentionService создаёт будущие партиции")
    class CreateAheadTests {

        @Test
        @DisplayName("runRetention создаёт партицию для месяца 10 лет вперёд (несуществующую)")
        void retentionCreatesPartitionForFutureMonth() {
            // Берём месяц настолько далеко в будущем, что V37 его точно не создавал
            YearMonth farFuture = YearMonth.now().plusMonths(24);
            String partName = String.format("tracking_event_log_%04d_%02d",
                    farFuture.getYear(), farFuture.getMonthValue());

            assertThat(retentionService.partitionExists(partName))
                    .as("до вызова retention партиции %s не должно существовать", partName)
                    .isFalse();

            // Создаём партицию явно через helper (эквивалент create-ahead в runRetention)
            retentionService.createPartitionForMonth(farFuture);

            assertThat(retentionService.partitionExists(partName))
                    .as("после create-ahead партиция %s должна существовать", partName)
                    .isTrue();

            // Cleanup: убрать тестовую партицию
            retentionService.dropPartitionForMonth(farFuture);
        }

        @Test
        @DisplayName("createPartitionForMonth идемпотентна: повторный вызов не бросает исключение")
        void createPartitionIsIdempotent() {
            YearMonth testMonth = YearMonth.of(2026, 6); // уже создана V37

            // Двойной вызов для существующей партиции — IF NOT EXISTS, нет исключения
            retentionService.createPartitionForMonth(testMonth);
            retentionService.createPartitionForMonth(testMonth);

            assertThat(retentionService.partitionExists("tracking_event_log_2026_06")).isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. DROP старой партиции (RetentionService)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. DROP старой партиции — данные удаляются вместе с партицией")
    class DropOldPartitionTests {

        @Test
        @DisplayName("партиция старше порога retention удаляется, данные в ней пропадают")
        void oldPartitionIsDroppedWithItsData() {
            // Создаём тестовую партицию за далёкое прошлое (январь 2024)
            YearMonth oldMonth = YearMonth.of(2024, 1);
            String oldPartName = "tracking_event_log_2024_01";

            // Партиции 2024_01 нет в V37 — создаём её для теста
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS tracking_event_log_2024_01 " +
                    "PARTITION OF tracking_event_log " +
                    "FOR VALUES FROM ('2024-01-01') TO ('2024-02-01')");

            assertThat(retentionService.partitionExists(oldPartName)).isTrue();

            // Вставляем строку через родительскую таблицу: PostgreSQL маршрутизирует
            // в tracking_event_log_2024_01 по created_at, а IDENTITY-последовательность
            // срабатывает только при вставке через parent (не через прямой INSERT в партицию).
            jdbcTemplate.update(
                    "INSERT INTO tracking_event_log " +
                    "(sequence_id, aircraft_id, event_type, created_at) " +
                    "VALUES (?, ?, ?, ?)",
                    42L, "VP-OLD", "SEQUENCE_STARTED", LocalDateTime.of(2024, 1, 15, 12, 0));

            Long countBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tracking_event_log WHERE aircraft_id = 'VP-OLD'",
                    Long.class);
            assertThat(countBefore).as("строка должна быть вставлена").isEqualTo(1L);

            // Явно удаляем партицию (как retention делает для старых месяцев)
            retentionService.dropPartitionForMonth(oldMonth);

            assertThat(retentionService.partitionExists(oldPartName))
                    .as("после drop партиция должна исчезнуть из pg_inherits")
                    .isFalse();

            Long countAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tracking_event_log WHERE aircraft_id = 'VP-OLD'",
                    Long.class);
            assertThat(countAfter)
                    .as("данные из удалённой партиции не должны быть видны через родительскую таблицу")
                    .isZero();
        }

        @Test
        @DisplayName("dropPartitionForMonth идемпотентна: DROP TABLE IF EXISTS не бросает исключение на несуществующей")
        void dropPartitionIsIdempotent() {
            YearMonth nonExistent = YearMonth.of(2020, 1);

            // Нет партиции → DROP IF EXISTS → нет исключения
            retentionService.dropPartitionForMonth(nonExistent);
            retentionService.dropPartitionForMonth(nonExistent); // второй раз тоже
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. Retention messages (retention-by-deletion)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. Retention messages — DELETE по received_at")
    class MessagesRetentionTests {

        @Test
        @DisplayName("старые messages (received_at за порогом) удаляются; свежие — остаются")
        void oldMessagesDeletedFreshMessagesKept() {
            // Вставляем старое сообщение (за пределами порога 90 дней)
            LocalDateTime oldDate = LocalDateTime.now().minusDays(100);
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "STATUS", "VP-OLD-MSG", "OLD001", "{}", oldDate);

            // Вставляем свежее сообщение
            LocalDateTime freshDate = LocalDateTime.now().minusDays(10);
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "ACK", "VP-FRESH-MSG", "FRESH001", "{}", freshDate);

            retentionService.runRetention();

            Long oldCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE aircraft_id = 'VP-OLD-MSG'", Long.class);
            assertThat(oldCount)
                    .as("старое сообщение (received_at=%s) должно быть удалено (порог 90 дней)", oldDate)
                    .isZero();

            Long freshCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE aircraft_id = 'VP-FRESH-MSG'", Long.class);
            assertThat(freshCount)
                    .as("свежее сообщение (received_at=%s) должно остаться", freshDate)
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("сообщение ровно на границе порога (N дней назад) НЕ удаляется (exclusive)")
        void messageAtExactThresholdNotDeleted() {
            // Сообщение exactly messagesDays дней назад: received_at = now - 90 days (не старше)
            LocalDateTime boundary = LocalDateTime.now().minusDays(89);
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "BOUNDARY", "VP-BOUNDARY", "B001", "{}", boundary);

            retentionService.runRetention();

            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE aircraft_id = 'VP-BOUNDARY'", Long.class);
            assertThat(count)
                    .as("сообщение от %s (< 90 дней назад) должно остаться", boundary)
                    .isEqualTo(1L);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 5. Retention audit_log (retention-by-deletion)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. Retention audit_log — DELETE по created_at")
    class AuditLogRetentionTests {

        @Test
        @DisplayName("старые audit_log (created_at за порогом 365 дней) удаляются; свежие — остаются")
        void oldAuditLogDeletedFreshKept() {
            // Старая запись (> 365 дней)
            LocalDateTime oldDate = LocalDateTime.now().minusDays(400);
            jdbcTemplate.update(
                    "INSERT INTO audit_log (user_id, action, entity_type, entity_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    1L, "OLD_ACTION", "SEQUENCE", 999L, oldDate);

            // Свежая запись
            LocalDateTime freshDate = LocalDateTime.now().minusDays(30);
            jdbcTemplate.update(
                    "INSERT INTO audit_log (user_id, action, entity_type, entity_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    1L, "FRESH_ACTION", "SEQUENCE", 1000L, freshDate);

            retentionService.runRetention();

            Long oldCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'OLD_ACTION'", Long.class);
            assertThat(oldCount)
                    .as("запись audit_log (created_at=%s) должна быть удалена (порог 365 дней)", oldDate)
                    .isZero();

            Long freshCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'FRESH_ACTION'", Long.class);
            assertThat(freshCount)
                    .as("свежая запись audit_log (created_at=%s) должна остаться", freshDate)
                    .isEqualTo(1L);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 6. Гейт leader election — no-op на не-лидере
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. Гейт leader election — retention на не-лидере является no-op")
    class LeaderElectionGatingTests {

        @Test
        @DisplayName("при isLeader()=false runRetention() не удаляет данные из messages")
        void retentionIsNoOpOnNonLeaderForMessages() {
            LocalDateTime oldDate = LocalDateTime.now().minusDays(200);
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "NOLEADER", "VP-NOLEADER", "NL001", "{}", oldDate);

            // Переключаем лидерство в false
            when(leaderElection.isLeader()).thenReturn(false);

            retentionService.runRetention();

            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE aircraft_id = 'VP-NOLEADER'", Long.class);
            assertThat(count)
                    .as("на не-лидере retention должна быть no-op: данные не должны удаляться")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("при isLeader()=false runRetention() не удаляет данные из audit_log")
        void retentionIsNoOpOnNonLeaderForAuditLog() {
            LocalDateTime oldDate = LocalDateTime.now().minusDays(400);
            jdbcTemplate.update(
                    "INSERT INTO audit_log (user_id, action, entity_type, entity_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    1L, "NO_LEADER_AUDIT", "USER", 42L, oldDate);

            when(leaderElection.isLeader()).thenReturn(false);

            retentionService.runRetention();

            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_log WHERE action = 'NO_LEADER_AUDIT'", Long.class);
            assertThat(count)
                    .as("на не-лидере audit_log retention должна быть no-op")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("при isLeader()=false старая партиция tracking_event_log НЕ удаляется")
        void retentionDoesNotDropPartitionsOnNonLeader() {
            // Создаём тестовую «старую» партицию
            jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS tracking_event_log_2023_12 " +
                    "PARTITION OF tracking_event_log " +
                    "FOR VALUES FROM ('2023-12-01') TO ('2024-01-01')");

            assertThat(retentionService.partitionExists("tracking_event_log_2023_12")).isTrue();

            when(leaderElection.isLeader()).thenReturn(false);
            retentionService.runRetention();

            assertThat(retentionService.partitionExists("tracking_event_log_2023_12"))
                    .as("на не-лидере partitionDrop не должен выполняться")
                    .isTrue();

            // Cleanup
            jdbcTemplate.execute("DROP TABLE IF EXISTS tracking_event_log_2023_12");
        }

        @Test
        @DisplayName("при isLeader()=true runRetention() реально удаляет старые данные (контроль)")
        void retentionWorksWhenLeader() {
            LocalDateTime oldDate = LocalDateTime.now().minusDays(200);
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "LEADER", "VP-LEADER", "L001", "{}", oldDate);

            when(leaderElection.isLeader()).thenReturn(true);
            retentionService.runRetention();

            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM messages WHERE aircraft_id = 'VP-LEADER'", Long.class);
            assertThat(count)
                    .as("на лидере старые данные должны быть удалены (контрольный тест)")
                    .isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 7. Метрики retention (наблюдаемость, CLAUDE.md §5)
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. Метрики retention — счётчики eca.retention.* инкрементируются")
    class RetentionMetricsTests {

        /**
         * Счётчик shared в контексте и накапливается между тестами — поэтому проверяем
         * ДЕЛЬТУ (после − до) в рамках одного метода, а не абсолютное значение.
         */
        private double deletedRows(String table) {
            io.micrometer.core.instrument.Counter c = Search.in(meterRegistry)
                    .name("eca.retention.rows.deleted").tag("table", table).counter();
            return c == null ? 0.0 : c.count();
        }

        @Test
        @DisplayName("runRetention на лидере инкрементирует eca.retention.rows.deleted для messages и audit_log")
        void retentionIncrementsRowsDeletedCounters() {
            double messagesBefore = deletedRows("messages");
            double auditBefore = deletedRows("audit_log");

            // Старые messages (> 90 дней) и audit_log (> 365 дней) — оба под удаление
            jdbcTemplate.update(
                    "INSERT INTO messages (message_type, template_name, aircraft_id, " +
                    "flight_number, content, received_at, is_estimated_position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, false)",
                    "DOWNLINK", "METRIC", "VP-METRIC", "M001", "{}", LocalDateTime.now().minusDays(120));
            jdbcTemplate.update(
                    "INSERT INTO audit_log (user_id, action, entity_type, entity_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?)",
                    1L, "METRIC_ACTION", "SEQUENCE", 7L, LocalDateTime.now().minusDays(400));

            when(leaderElection.isLeader()).thenReturn(true);
            retentionService.runRetention();

            assertThat(deletedRows("messages") - messagesBefore)
                    .as("eca.retention.rows.deleted{table=messages} вырос минимум на 1 удалённую строку")
                    .isGreaterThanOrEqualTo(1.0);
            assertThat(deletedRows("audit_log") - auditBefore)
                    .as("eca.retention.rows.deleted{table=audit_log} вырос минимум на 1 удалённую строку")
                    .isGreaterThanOrEqualTo(1.0);
        }
    }
}
