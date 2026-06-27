-- P6-1: HA — leader election планировщиков (single-fire в кластере).
--
-- Lease-таблица: одна реплика держит «аренду» лидерства с TTL (lease_until). Лидер периодически
-- продлевает аренду (renewed_at/lease_until); при падении лидера аренда протухает и другая реплика
-- атомарно перехватывает её через INSERT ... ON CONFLICT DO UPDATE ... WHERE (см. LeaderElectionService).
--
-- Почему lease-таблица, а не pg_advisory_lock: advisory lock привязан к СЕССИИ (соединению), что плохо
-- сочетается с пулом Hikari (соединения возвращаются в пул). Lease переоценивается на каждом heartbeat
-- из обычного пулового соединения — устойчиво к разрывам соединений. Импортозамещение: только PostgreSQL,
-- без ShedLock/Quartz/ZooKeeper (та же философия, что durable WAIT-таймауты P1-5).
--
-- ВАЖНО: корректность single-fire НЕ зависит ТОЛЬКО от этой таблицы — атомарный DB-claim планировщиков
-- (claimExpiredTimeout/claimPending, P1-5/P2-3) остаётся defense-in-depth: даже при кратком раздвоении
-- лидерства двойного срабатывания не будет. Leader election — это про ЭФФЕКТИВНОСТЬ (не опрашивать всеми
-- репликами сразу), а не единственный механизм корректности.
CREATE TABLE leader_election (
    lock_name    VARCHAR(64)  PRIMARY KEY,
    holder_id    VARCHAR(255) NOT NULL,
    acquired_at  TIMESTAMP    NOT NULL,
    renewed_at   TIMESTAMP    NOT NULL,
    lease_until  TIMESTAMP    NOT NULL
);
