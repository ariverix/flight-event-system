package ru.protectinfotrans.eca.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.BaseIntegrationTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-1: leader election планировщиков на PostgreSQL (single-fire в кластере).
 *
 * <p>Моделирует 2+ реплики как несколько экземпляров {@link LeaderElectionService} с РАЗНЫМИ
 * holderId поверх одной БД (реальный Postgres из {@link BaseIntegrationTest}). Доказывает:
 * <ul>
 *   <li>из конкурирующих реплик лидером становится РОВНО ОДНА (атомарный upsert аренды);</li>
 *   <li>вторая реплика НЕ может перехватить ещё валидную аренду;</li>
 *   <li>при «падении» лидера (протухание аренды/release) лидерство перехватывает другая реплика;</li>
 *   <li>{@code isLeader()} учитывает локальный дедлайн аренды.</li>
 * </ul>
 *
 * <p><b>Изоляция:</b> тест использует собственное {@code lock_name} ({@link #TEST_LOCK}),
 * отдельное от продакшн-слота {@code "scheduler"}, который heartbeat'ит реальный бин
 * {@link LeaderElectionService} в контексте — чтобы фоновые тики не влияли на детерминизм.
 *
 * <p>Корректность single-fire самих таймаутов/доставок (claim в БД) проверяется в P1-5/P2-3;
 * здесь — именно слой выбора лидера, гейтящий @Scheduled-тики (WaitTimeoutScheduler/
 * OutboundMessageDeliveryScheduler.scheduledPoll).
 */
@DisplayName("P6-1: leader election (single-fire в кластере)")
class P6_1_LeaderElectionIntTest extends BaseIntegrationTest {

    private static final String TEST_LOCK = "p6-1-test-lock";

    private LeaderElectionService node(String holderId) {
        return new LeaderElectionService(jdbcTemplate, TEST_LOCK, holderId, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("из двух реплик лидером становится ровно одна; вторая не перехватывает валидную аренду")
    void onlyOneReplicaBecomesLeader() {
        LeaderElectionService nodeA = node("node-A");
        LeaderElectionService nodeB = node("node-B");

        boolean aAcquired = nodeA.tryAcquireLeadership();
        boolean bAcquired = nodeB.tryAcquireLeadership();

        assertThat(aAcquired).as("первая реплика захватывает свободную аренду").isTrue();
        assertThat(bAcquired).as("вторая реплика НЕ перехватывает ещё валидную аренду").isFalse();

        assertThat(nodeA.isLeader()).isTrue();
        assertThat(nodeB.isLeader()).isFalse();

        // В БД ровно одна строка лидерства на этот lock, и держит её node-A
        String holder = jdbcTemplate.queryForObject(
                "SELECT holder_id FROM leader_election WHERE lock_name = ?", String.class, TEST_LOCK);
        assertThat(holder).isEqualTo("node-A");
        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leader_election WHERE lock_name = ?", Long.class, TEST_LOCK);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    @DisplayName("лидер продлевает свою аренду на повторных попытках (renew, без потери лидерства)")
    void leaderRenewsItsOwnLease() {
        LeaderElectionService nodeA = node("node-A");

        assertThat(nodeA.tryAcquireLeadership()).isTrue();
        // повторный вызов тем же holderId — продление, остаётся лидером
        assertThat(nodeA.tryAcquireLeadership()).isTrue();
        assertThat(nodeA.isLeader()).isTrue();

        // конкурент по-прежнему не может зайти
        assertThat(node("node-B").tryAcquireLeadership()).isFalse();
    }

    @Test
    @DisplayName("при протухании аренды лидера вторая реплика перехватывает лидерство")
    void leadershipFailsOverWhenLeaseExpires() {
        LeaderElectionService nodeA = node("node-A");
        LeaderElectionService nodeB = node("node-B");

        assertThat(nodeA.tryAcquireLeadership()).isTrue();
        assertThat(nodeB.tryAcquireLeadership()).isFalse();

        // Симулируем «падение» лидера node-A: его аренда протухает (он больше не продлевает её).
        jdbcTemplate.update(
                "UPDATE leader_election SET lease_until = NOW() - INTERVAL '1 minute' WHERE lock_name = ?",
                TEST_LOCK);

        // Теперь node-B перехватывает протухшую аренду
        assertThat(nodeB.tryAcquireLeadership()).as("node-B перехватывает протухшую аренду").isTrue();
        assertThat(nodeB.isLeader()).isTrue();

        // А node-A больше не может вернуть лидерство, пока node-B держит валидную аренду
        assertThat(nodeA.tryAcquireLeadership()).as("node-A не возвращает лидерство у живого node-B").isFalse();

        String holder = jdbcTemplate.queryForObject(
                "SELECT holder_id FROM leader_election WHERE lock_name = ?", String.class, TEST_LOCK);
        assertThat(holder).isEqualTo("node-B");
    }

    @Test
    @DisplayName("release лидера освобождает аренду — другая реплика сразу становится лидером")
    void releaseLetsAnotherReplicaTakeOverImmediately() {
        LeaderElectionService nodeA = node("node-A");
        LeaderElectionService nodeB = node("node-B");

        assertThat(nodeA.tryAcquireLeadership()).isTrue();
        nodeA.releaseOnShutdown();

        assertThat(nodeA.isLeader()).as("после release бывший лидер не считается лидером").isFalse();
        // строки лидерства больше нет — node-B захватывает свободный слот
        assertThat(nodeB.tryAcquireLeadership()).isTrue();
        assertThat(nodeB.isLeader()).isTrue();
    }

    @Test
    @DisplayName("isLeader() ложь, если локальная аренда истекла (нулевая длительность аренды)")
    void isLeaderFalseWhenLocalLeaseExpired() {
        // Аренда нулевой длительности: захват успешен в БД, но локальный дедлайн уже в прошлом,
        // поэтому isLeader() сразу false (защита от действий лидера с протухшей арендой).
        LeaderElectionService shortLease =
                new LeaderElectionService(jdbcTemplate, TEST_LOCK, "node-short", Duration.ZERO);

        assertThat(shortLease.tryAcquireLeadership()).as("захват в БД успешен").isTrue();
        assertThat(shortLease.isLeader()).as("но локальная аренда уже истекла → не лидер").isFalse();
    }
}
