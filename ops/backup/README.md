# ops/backup — Бэкап и восстановление PostgreSQL для ECA System

## Обзор

Каталог содержит операционные скрипты и руководство по резервному копированию и восстановлению
базы данных PostgreSQL 16 системы ECA. Покрывает два подхода:
1. **Логический бэкап** (`pg_dump` / `pg_restore`) — быстро, гибко, portable.
2. **PITR** (Point-In-Time Recovery через WAL archiving + `pg_basebackup`) — низкие RPO, руководство ниже.

> Scope-граница: текущая архитектура ECA — один узел PostgreSQL (HA-реплики — P6-1,
> ещё не реализовано). Настоящий replica-failover → P6-1 (follow-up).
> Настоящий раздел: бэкап/восстановление + chaos-устойчивость одиночного узла.

---

## 1. Логический бэкап (pg_dump)

### Быстрый старт

```sh
# Настройте переменные окружения (или .pgpass)
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=eca_db
export DB_USER=eca_user
export DB_PASSWORD=eca_password
export BACKUP_DIR=/var/backups/eca
export RETENTION_DAYS=7

# Создать дамп (custom format, сжатие встроено)
./ops/backup/pg_backup.sh
# → /var/backups/eca/eca_backup_20260101_030000.dump

# Восстановить из дампа
./ops/backup/pg_restore.sh /var/backups/eca/eca_backup_20260101_030000.dump
```

### Формат и флаги

| Флаг | Описание |
|------|----------|
| `--format=custom` | Бинарный custom-формат: поддерживает сжатие (gzip, level 6 по умолчанию), параллельное восстановление (`-j N`), выборочное восстановление отдельных объектов. |
| `--no-owner` | При восстановлении не переносить владельца объектов — совместимо с любым суперпользователем. |
| `--no-privileges` | Не дампить GRANT/REVOKE — права назначаются при деплое через DDL/миграцию. |

### Расписание (рекомендуется cron)

```cron
# Ежедневный бэкап в 03:00
0 3 * * * /path/to/ops/backup/pg_backup.sh >> /var/log/eca-backup.log 2>&1

# Еженедельный (с большим retention) — опционально
0 2 * * 0 RETENTION_DAYS=30 /path/to/ops/backup/pg_backup.sh >> /var/log/eca-backup-weekly.log 2>&1
```

### Восстановление: runbook (step-by-step)

1. **Создать пустую целевую БД** (если восстановление в новый экземпляр):
   ```sh
   createdb -h $DB_HOST -U $DB_USER -O $DB_USER eca_db_restore
   ```

2. **Восстановить схему и данные**:
   ```sh
   export DB_NAME=eca_db_restore
   ./ops/backup/pg_restore.sh /var/backups/eca/eca_backup_20260101_030000.dump
   ```

3. **Проверить целостность схемы** (версия Flyway):
   ```sql
   SELECT version, description, success
   FROM flyway_schema_history
   ORDER BY installed_rank DESC
   LIMIT 5;
   ```
   Ожидаемый результат: последняя строка — `version='35'`, `success=true`.

4. **Проверить доменные данные**:
   ```sql
   SELECT COUNT(*) FROM sequences;
   SELECT COUNT(*) FROM messages;
   SELECT COUNT(*) FROM execution_instances WHERE status IN ('RUNNING', 'WAITING');
   ```

5. **Переключить приложение** на восстановленную БД (обновить `DB_URL` env/config-map).

### RTO/RPO для логического бэкапа

| Метрика | Ориентир |
|---------|----------|
| RPO (потеря данных) | 24 ч (при ежедневном бэкапе) |
| RTO (время восстановления) | 15–60 мин (зависит от размера БД и дисковой скорости) |

---

## 2. PITR (Point-In-Time Recovery)

> Руководство + конфиг-примеры. Инфраструктуру разворачивает DevOps в рамках P6/P8.
> Команды верифицированы для PostgreSQL 16.

PITR позволяет восстановить БД на любой момент времени (до секунды). Требует:
- WAL archiving (непрерывная запись журнала в внешнее хранилище)
- Base backup (`pg_basebackup`)

### 2.1. Включение WAL archiving

В `postgresql.conf` (или через ALTER SYSTEM):
```ini
wal_level = replica          # минимум для archiving
archive_mode = on
archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/%f'
# Для S3-совместимого хранилища (импортозамещение — Ceph/MinIO):
# archive_command = 'mc cp %p minio/eca-wal-archive/%f'
archive_timeout = 300        # принудительно закрывать WAL-сегмент каждые 5 мин
```

Применить без рестарта (только archive_timeout без перезапуска):
```sql
SELECT pg_reload_conf();
```
Для `archive_mode` и `wal_level` требуется рестарт PostgreSQL.

Проверить, что archiving работает:
```sql
SELECT * FROM pg_stat_archiver;
-- last_failed_time должен быть NULL или очень старым
```

### 2.2. Base backup

```sh
# Раз в сутки / перед крупными изменениями схемы
pg_basebackup \
    --host=localhost \
    --port=5432 \
    --username=eca_user \
    --pgdata=/var/backups/eca/basebackup/$(date +%Y%m%d) \
    --format=tar \
    --gzip \
    --wal-method=stream \
    --progress \
    --verbose
```

### 2.3. Восстановление до точки времени

```sh
# 1. Остановить PostgreSQL
systemctl stop postgresql

# 2. Очистить $PGDATA и распаковать base backup
rm -rf /var/lib/postgresql/16/main/*
tar -xzf /var/backups/eca/basebackup/20260101/base.tar.gz \
    -C /var/lib/postgresql/16/main/
tar -xzf /var/backups/eca/basebackup/20260101/pg_wal.tar.gz \
    -C /var/lib/postgresql/16/main/pg_wal/

# 3. Создать recovery.conf (PostgreSQL 16: параметры в postgresql.conf + standby.signal/recovery.signal)
touch /var/lib/postgresql/16/main/recovery.signal

# В postgresql.conf добавить:
cat >> /var/lib/postgresql/16/main/postgresql.conf << 'EOF'
restore_command = 'cp /wal-archive/%f %p'
recovery_target_time = '2026-01-01 12:00:00'   # целевой момент (UTC)
recovery_target_action = promote
EOF

# 4. Запустить PostgreSQL — он проиграет WAL до указанного момента
systemctl start postgresql

# 5. Проверить восстановление
psql -U eca_user -c "SELECT pg_is_in_recovery();"
# → f (false) — восстановление завершено, БД в нормальном режиме
```

### RTO/RPO для PITR

| Метрика | Ориентир |
|---------|----------|
| RPO (потеря данных) | ≤ 5 мин (при `archive_timeout=300`) |
| RTO (время восстановления) | 30–120 мин (зависит от размера БД и объёма WAL для replay) |

---

## 3. Интеграционный тест восстановления (Testcontainers)

Автоматизированное доказательство работоспособности процедуры восстановления:

```
backend/src/test/java/.../reliability/P5_4_BackupRestoreIntTest.java
```

**Что тест доказывает:**
1. Запускает PostgreSQL-контейнер (source), накатывает Flyway V1–V35, вставляет доменные данные (последовательность VP-BQR/SU1234, сообщения, инстанс).
2. Выполняет `pg_dump` внутри контейнера (`Container.execInContainer`), копирует дамп на хост.
3. Запускает второй чистый PostgreSQL-контейнер (target), копирует дамп, выполняет `pg_restore`.
4. Проверяет через JDBC:
   - Количество строк в `sequences`, `messages`, `steps` совпадает с source.
   - Конкретная тестовая запись (`sequences.name = 'P5-4 Restore Test'`) присутствует.
   - `flyway_schema_history` содержит ровно 35 успешных миграций (V35 — последняя).

---

## 4. Scope-граница: настоящий replica-failover → P6-1

Текущая ECA развёрнута на **одном узле PostgreSQL** (HA-реплики, leader election — P6-1).
Сценарии из этого каталога покрывают:
- Полный бэкап и восстановление одиночного узла.
- Устойчивость приложения к временной недоступности БД (chaos-тесты, `P5_4_DatabaseChaosIntTest`).
- Устойчивость исходящего канала (circuit breaker, `P5_4_ChannelChaosIntTest`).

**Не покрывается здесь (P6-1):**
- Потоковая репликация (streaming replication, synchronous/asynchronous).
- Автоматический failover с переключением лидера (Patroni / pgPool-II / repmgr).
- Балансировка нагрузки на реплики.

Документация chaos-сценариев: `docs/runbooks/chaos-failover.md`.
