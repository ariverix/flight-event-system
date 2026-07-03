package ru.protectinfotrans.eca.cluster;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * P6-1: lease-based leader election на PostgreSQL.
 *
 * <p>Каждая реплика имеет уникальный {@link #holderId}. Лидерство — строка в таблице
 * {@code leader_election} с арендой (lease) на {@link #leaseDuration}. Захват/продление —
 * единый атомарный {@code INSERT ... ON CONFLICT (lock_name) DO UPDATE ... WHERE} (см.
 * {@link #tryAcquireLeadership()}): аренду можно перехватить, только если она протухла
 * ({@code lease_until < now}) или уже принадлежит этой реплике. Postgres гарантирует атомарность
 * upsert'а, поэтому из N конкурирующих реплик лидером становится ровно одна.
 *
 * <p><b>Старт:</b> на {@link ApplicationReadyEvent} (схема уже мигрирована Flyway) делается первая
 * попытка захвата — одиночная реплика становится лидером сразу, без ожидания первого heartbeat.
 * <b>Heartbeat:</b> каждые 10 c аренда продлевается ({@link #heartbeat()}); если БД временно
 * недоступна или идёт межтестовый Flyway-clean — тик молча пропускается, локальная аренда протухнет
 * и реплика перестанет считаться лидером, пока не перезахватит. <b>Shutdown:</b> {@link #releaseOnShutdown()}
 * удаляет строку, ускоряя перехват лидерства другой репликой (иначе ждали бы протухания аренды).
 *
 * <p><b>Импортозамещение:</b> только PostgreSQL, без ShedLock/Quartz/ZooKeeper (см. ADR-0004,
 * та же философия, что durable WAIT-таймауты P1-5). Корректность single-fire обеспечивает
 * DB-claim в планировщиках, см. {@link LeaderElection}.
 */
@Component
@Slf4j
public class LeaderElectionService implements LeaderElection {

    /** Единственный «слот» лидерства для всех @Scheduled-поллеров кластера. */
    static final String LOCK_NAME = "scheduler";

    private final JdbcTemplate jdbc;
    private final String lockName;
    private final String holderId;
    private final Duration leaseDuration;

    /** Результат последней попытки захвата (volatile — читается из @Scheduled-потоков планировщиков). */
    private volatile boolean leader = false;
    /**
     * P2-3 (гигиена): взводится на {@link ApplicationReadyEvent} ({@link #acquireOnStartup}) — до
     * готовности приложения {@link #heartbeat()} не тикает. Собственный флаг (а не инъекция
     * {@code ApplicationReadiness}) намеренно: этот бин САМ слушает {@code ApplicationReadyEvent}
     * для {@code acquireOnStartup}, поэтому не создаём лишнюю зависимость и не трогаем оба
     * конструктора (в т.ч. пакетный тестовый).
     */
    private volatile boolean applicationReady = false;
    /** Локальный дедлайн аренды: даже если флаг {@link #leader} устарел, по истечении аренды считаемся не-лидером. */
    private volatile Instant localLeaseExpiry = Instant.MIN;

    @Autowired
    public LeaderElectionService(JdbcTemplate jdbc) {
        this(jdbc, LOCK_NAME, defaultHolderId(), Duration.ofSeconds(30));
    }

    /**
     * Пакетный конструктор для тестов: явные lockName (изоляция от продакшн-слота {@link #LOCK_NAME},
     * который heartbeat'ит реальный бин), holderId и длительность аренды.
     */
    LeaderElectionService(JdbcTemplate jdbc, String lockName, String holderId, Duration leaseDuration) {
        this.jdbc = jdbc;
        this.lockName = lockName;
        this.holderId = holderId;
        this.leaseDuration = leaseDuration;
    }

    /** Уникальный идентификатор реплики: {@code pid@host} (RuntimeMXBean) + случайный UUID. */
    private static String defaultHolderId() {
        return ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void acquireOnStartup() {
        this.applicationReady = true;
        try {
            tryAcquireLeadership();
            if (leader) {
                log.info("Leader election: replica {} acquired leadership on startup", holderId);
            }
        } catch (Exception e) {
            log.warn("Leader election: startup acquire failed (will retry on heartbeat): {}", e.toString());
        }
    }

    @Scheduled(fixedRate = 10_000)
    public void heartbeat() {
        // P2-3: не тикаем до готовности приложения — лидерство впервые захватывается в
        // acquireOnStartup на ApplicationReadyEvent, heartbeat лишь продлевает аренду после.
        if (!applicationReady) {
            return;
        }
        try {
            boolean was = leader;
            tryAcquireLeadership();
            if (leader && !was) {
                log.info("Leader election: replica {} became leader", holderId);
            } else if (!leader && was) {
                log.info("Leader election: replica {} lost leadership", holderId);
            }
        } catch (Exception e) {
            // межтестовое Flyway-clean окно / временная недоступность БД — не валим планировщик;
            // на следующем тике перезахватим, а до тех пор localLeaseExpiry защищает от ложного лидерства.
            log.debug("Leader election heartbeat failed (will retry next tick): {}", e.toString());
        }
    }

    /**
     * Атомарный захват/продление аренды лидерства. Возвращает {@code true}, если ПОСЛЕ операции
     * аренду держит эта реплика. Захватить чужую аренду можно только если она протухла
     * ({@code lease_until < now}); продлить свою — всегда.
     */
    public boolean tryAcquireLeadership() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        int rows = jdbc.update("""
                INSERT INTO leader_election (lock_name, holder_id, acquired_at, renewed_at, lease_until)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (lock_name) DO UPDATE
                    SET holder_id   = EXCLUDED.holder_id,
                        renewed_at  = EXCLUDED.renewed_at,
                        lease_until = EXCLUDED.lease_until,
                        acquired_at = CASE WHEN leader_election.holder_id = EXCLUDED.holder_id
                                           THEN leader_election.acquired_at
                                           ELSE EXCLUDED.acquired_at END
                    WHERE leader_election.lease_until < ?
                       OR leader_election.holder_id = EXCLUDED.holder_id
                """, lockName, holderId, now, now, leaseUntil, now);

        boolean acquired = rows > 0;
        this.leader = acquired;
        if (acquired) {
            this.localLeaseExpiry = Instant.now().plus(leaseDuration);
        }
        return acquired;
    }

    @Override
    public boolean isLeader() {
        return leader && Instant.now().isBefore(localLeaseExpiry);
    }

    @PreDestroy
    public void releaseOnShutdown() {
        try {
            jdbc.update("DELETE FROM leader_election WHERE lock_name = ? AND holder_id = ?", lockName, holderId);
            leader = false;
            log.info("Leader election: replica {} released leadership on shutdown", holderId);
        } catch (Exception e) {
            log.debug("Leader election release on shutdown failed: {}", e.toString());
        }
    }

    /** Идентификатор этой реплики (для тестов/диагностики). */
    String holderId() {
        return holderId;
    }
}
