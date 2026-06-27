package ru.protectinfotrans.eca;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.cluster.LeaderElection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * P6-2: Retention (удержание данных) для трёх высоконагруженных таблиц.
 *
 * <p><b>tracking_event_log</b> (нативно партиционирована с V37):
 * <ul>
 *   <li>Создаёт именованные партиции вперёд на {@code createAheadMonths} месяцев
 *       (CREATE TABLE IF NOT EXISTS … PARTITION OF) — идемпотентно.</li>
 *   <li>Удаляет партиции старше {@code trackingEventLogMonths} месяцев
 *       (DROP TABLE IF EXISTS) — O(1) по данным, без долгих локов на содержимое.</li>
 * </ul>
 *
 * <p><b>messages</b> и <b>audit_log</b> (flat таблицы):
 * <ul>
 *   <li>DELETE WHERE received_at/created_at &lt; порога — retention-by-deletion.</li>
 *   <li>Не переведены на нативное партиционирование: сложные JPQL-запросы критериев ECA
 *       в MessageJpaRepository (from-this-point-only, exists*, position-queries) и
 *       cross-module writers audit_log потребовали бы @IdClass + каскадных изменений
 *       в множестве тестов — ломает > 10 тестов, что запрещено по CLAUDE.md задаче P6-2.</li>
 * </ul>
 *
 * <p><b>Leader-election гейт:</b> метод {@link #runRetention()} мгновенно возвращает управление,
 * если {@link LeaderElection#isLeader()} == false — в кластере чистку выполняет только
 * реплика-лидер. Defense-in-depth: DELETE/DROP идемпотентны, повторный запуск двумя
 * репликами одновременно не ломает данные (IF NOT EXISTS / IF EXISTS / WHERE).
 *
 * <p><b>Modulith:</b> класс находится в корневом (shared) пакете {@code ru.protectinfotrans.eca} —
 * может обращаться к {@link LeaderElection} из модуля {@code cluster} через его публичный API
 * и к {@link JdbcTemplate} напрямую (не через модульные порты — retention это инфра-операция
 * уровня DBA, не бизнес-логика модулей). {@code ApplicationModules.verify()} проходит.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionService {

    private final LeaderElection leaderElection;
    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties props;
    /**
     * P6-2: метрики retention (наблюдаемость нового пути, CLAUDE.md §5). Micrometer кэширует
     * метры по имени+тегам, поэтому {@code meterRegistry.counter(...)} в точке инкремента
     * идемпотентен (возвращает тот же инстанс). Алертинг: «retention не работал N дней» —
     * по отсутствию роста {@code eca.retention.*}.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Основной метод retention. Запускается по расписанию (дефолт: ежедневно в 03:00)
     * и при явном вызове из тестов.
     *
     * <p>Если реплика не является лидером — no-op (log.debug + return).
     */
    @Scheduled(cron = "${app.retention.cron:0 0 3 * * *}")
    public void runRetention() {
        if (!leaderElection.isLeader()) {
            log.debug("Retention: не лидер, пропускаем");
            return;
        }
        log.info("Retention: старт (лидер=true, параметры: tel={}мес, msg={}дн, audit={}дн, ahead={}мес)",
                props.getTrackingEventLogMonths(), props.getMessagesDays(),
                props.getAuditLogDays(), props.getCreateAheadMonths());
        try {
            manageTrackingEventLogPartitions();
        } catch (Exception e) {
            log.error("Retention: ошибка управления партициями tracking_event_log", e);
        }
        try {
            deleteOldMessages();
        } catch (Exception e) {
            log.error("Retention: ошибка удаления старых messages", e);
        }
        try {
            deleteOldAuditLog();
        } catch (Exception e) {
            log.error("Retention: ошибка удаления старых audit_log", e);
        }
        log.info("Retention: завершён");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // tracking_event_log: управление партициями
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Создаёт именованные партиции вперёд (create-ahead) и удаляет старые.
     */
    private void manageTrackingEventLogPartitions() {
        YearMonth current = YearMonth.now();

        // Создать партиции для текущего и следующих createAheadMonths месяцев
        for (int offset = 0; offset <= props.getCreateAheadMonths(); offset++) {
            YearMonth month = current.plusMonths(offset);
            createPartitionIfNeeded(month);
        }

        // Удалить партиции старше trackingEventLogMonths месяцев
        YearMonth cutoff = current.minusMonths(props.getTrackingEventLogMonths());
        List<String> partitions = getNamedPartitions();
        for (String partName : partitions) {
            YearMonth partMonth = parsePartitionMonth(partName);
            if (partMonth != null && partMonth.isBefore(cutoff)) {
                dropPartition(partName);
            }
        }
    }

    /**
     * Создаёт партицию для указанного месяца, если её ещё нет.
     * Идемпотентно: IF NOT EXISTS предотвращает ошибку при повторном запуске.
     *
     * <p>Если в DEFAULT-партиции уже есть данные за этот месяц, PostgreSQL
     * откажет в создании — ошибка логируется как WARN и пропускается (данные
     * остаются в DEFAULT, retention для DEFAULT работает через DELETE).
     */
    private void createPartitionIfNeeded(YearMonth month) {
        String partName = partitionName(month);
        String fromDate = month.atDay(1).toString();           // YYYY-MM-DD
        String toDate   = month.plusMonths(1).atDay(1).toString();
        try {
            jdbcTemplate.execute(String.format(
                    "CREATE TABLE IF NOT EXISTS %s PARTITION OF tracking_event_log " +
                    "FOR VALUES FROM ('%s') TO ('%s')",
                    partName, fromDate, toDate));
            log.debug("Retention: партиция {} создана или уже существует", partName);
        } catch (Exception e) {
            log.warn("Retention: не удалось создать партицию {} (возможный конфликт с DEFAULT): {}",
                    partName, e.getMessage());
        }
    }

    /**
     * Удаляет устаревшую партицию. DROP TABLE IF EXISTS удаляет партицию и автоматически
     * убирает её из parent-таблицы (не нужен предварительный DETACH).
     */
    private void dropPartition(String partName) {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + partName);
            meterRegistry.counter("eca.retention.partitions.dropped", "table", "tracking_event_log").increment();
            log.info("Retention: удалена старая партиция {}", partName);
        } catch (Exception e) {
            log.warn("Retention: не удалось удалить партицию {}: {}", partName, e.getMessage());
        }
    }

    /**
     * Возвращает имена именованных (YYYY_MM) партиций tracking_event_log из pg_catalog.
     * DEFAULT-партиция (tracking_event_log_default) не попадает в результат — у неё
     * нет числового суффикса.
     */
    private List<String> getNamedPartitions() {
        return jdbcTemplate.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_inherits i  ON c.oid = i.inhrelid
                JOIN pg_class p     ON i.inhparent = p.oid
                JOIN pg_namespace ns ON c.relnamespace = ns.oid
                WHERE p.relname  = 'tracking_event_log'
                  AND ns.nspname = 'public'
                  AND c.relname  ~ '^tracking_event_log_[0-9]{4}_[0-9]{2}$'
                ORDER BY c.relname
                """, String.class);
    }

    /** Формирует имя партиции в стиле tracking_event_log_YYYY_MM. */
    private String partitionName(YearMonth month) {
        return String.format("tracking_event_log_%04d_%02d", month.getYear(), month.getMonthValue());
    }

    /**
     * Парсит год и месяц из имени партиции tracking_event_log_YYYY_MM.
     * Возвращает null при неожиданном формате.
     */
    private YearMonth parsePartitionMonth(String partName) {
        // tracking_event_log_2026_06 → parts[-2]=2026, parts[-1]=06
        try {
            String[] parts = partName.split("_");
            int year  = Integer.parseInt(parts[parts.length - 2]);
            int month = Integer.parseInt(parts[parts.length - 1]);
            return YearMonth.of(year, month);
        } catch (Exception e) {
            log.debug("Retention: не удалось распарсить месяц партиции: {}", partName);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // messages: retention-by-deletion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Удаляет из messages строки старше messagesDays дней по received_at.
     * Retention-by-deletion: messages не партиционируется нативно (см. javadoc класса).
     */
    private void deleteOldMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(props.getMessagesDays());
        int deleted = jdbcTemplate.update(
                "DELETE FROM messages WHERE received_at < ?", cutoff);
        if (deleted > 0) {
            meterRegistry.counter("eca.retention.rows.deleted", "table", "messages").increment(deleted);
            log.info("Retention: удалено {} старых записей messages (received_at < {})", deleted, cutoff);
        } else {
            log.debug("Retention: старых записей messages не найдено (порог: {})", cutoff);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // audit_log: retention-by-deletion
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Удаляет из audit_log строки старше auditLogDays дней по created_at.
     * Retention-by-deletion: audit_log не партиционируется нативно (см. javadoc класса).
     */
    private void deleteOldAuditLog() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(props.getAuditLogDays());
        int deleted = jdbcTemplate.update(
                "DELETE FROM audit_log WHERE created_at < ?", cutoff);
        if (deleted > 0) {
            meterRegistry.counter("eca.retention.rows.deleted", "table", "audit_log").increment(deleted);
            log.info("Retention: удалено {} старых записей audit_log (created_at < {})", deleted, cutoff);
        } else {
            log.debug("Retention: старых записей audit_log не найдено (порог: {})", cutoff);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Геттеры для тестирования (package-visible)
    // ═══════════════════════════════════════════════════════════════════════

    /** Для теста: подсчёт именованных партиций. */
    int countNamedPartitions() {
        return getNamedPartitions().size();
    }

    /** Для теста: существует ли конкретная партиция. */
    boolean partitionExists(String partName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_class c " +
                "JOIN pg_inherits i ON c.oid = i.inhrelid " +
                "JOIN pg_class p ON i.inhparent = p.oid " +
                "WHERE p.relname = 'tracking_event_log' AND c.relname = ?",
                Long.class, partName);
        return count != null && count > 0;
    }

    /** Для теста: создать партицию явно (без await расписания). */
    void createPartitionForMonth(YearMonth month) {
        createPartitionIfNeeded(month);
    }

    /** Для теста: удалить партицию явно. */
    void dropPartitionForMonth(YearMonth month) {
        dropPartition(partitionName(month));
    }
}
