# Ранбук: Резервное копирование и восстановление БД

**Область:** PostgreSQL 16, база `eca_db`. Скрипты: `ops/backup/pg_backup.sh`, `ops/backup/pg_restore.sh`.

---

## Содержание

1. [Логический бэкап (pg_dump)](#логический-бэкап-pg_dump)
2. [Восстановление (pg_restore)](#восстановление-pg_restore)
3. [Point-in-Time Recovery (PITR)](#point-in-time-recovery-pitr)
4. [Проверка консистентности после восстановления](#проверка-консистентности-после-восстановления)
5. [Расписание и хранение](#расписание-и-хранение)

---

## Логический бэкап (pg_dump)

Скрипт `ops/backup/pg_backup.sh` создаёт логический дамп в формате `custom` (бинарный, сжатый, пригоден только для `pg_restore`, не для `psql`).

### Параметры скрипта

| Переменная окружения | По умолчанию | Описание |
|---------------------|---|---|
| `DB_HOST` | `localhost` | Хост PostgreSQL |
| `DB_PORT` | `5432` | Порт PostgreSQL |
| `DB_NAME` | `eca_db` | Имя базы данных |
| `DB_USER` | `eca_user` | Пользователь PostgreSQL |
| `DB_PASSWORD` | (из `.pgpass`) | Пароль. В prod рекомендуется `.pgpass` вместо env |
| `BACKUP_DIR` | `/var/backups/eca` | Каталог для дампов |
| `RETENTION_DAYS` | `7` | Хранить дампы N суток (старые удаляются автоматически) |

### Запуск бэкапа

```bash
# Выставить переменные (или использовать .pgpass для пароля)
export DB_HOST=prod-postgres.internal
export DB_PORT=5432
export DB_NAME=eca_db
export DB_USER=eca_user
export DB_PASSWORD=PROD_DB_PASSWORD
export BACKUP_DIR=/var/backups/eca
export RETENTION_DAYS=14

# Запустить бэкап
/path/to/flight-event-system/ops/backup/pg_backup.sh

# Скрипт выводит путь к созданному файлу:
# /var/backups/eca/eca_backup_20260628_030000.dump
```

Каждый запуск создаёт два файла:
- `eca_backup_YYYYMMDD_HHMMSS.dump` — дамп в custom-формате
- `eca_backup_YYYYMMDD_HHMMSS.log` — лог выполнения pg_dump

### Автоматизация через cron

```cron
# Бэкап каждый день в 02:00 (до retention-задачи в 03:00)
0 2 * * * DB_HOST=prod-postgres.internal DB_USER=eca_user BACKUP_DIR=/var/backups/eca \
  /opt/eca/ops/backup/pg_backup.sh >> /var/log/eca-backup.log 2>&1
```

### Kubernetes: бэкап из CronJob

```yaml
# Пример K8s CronJob для автоматических бэкапов
apiVersion: batch/v1
kind: CronJob
metadata:
  name: eca-db-backup
  namespace: eca
spec:
  schedule: "0 2 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: OnFailure
          containers:
            - name: pg-backup
              image: postgres:16-alpine
              command:
                - /bin/sh
                - -c
                - |
                  pg_dump \
                    --host=$DB_HOST --port=5432 \
                    --username=$DB_USER --dbname=$DB_NAME \
                    --format=custom --no-owner --no-privileges \
                    --file=/backup/eca_backup_$(date +%Y%m%d_%H%M%S).dump
              env:
                - name: DB_HOST
                  value: "prod-postgres.internal"
                - name: DB_NAME
                  value: "eca_db"
                - name: DB_USER
                  valueFrom:
                    secretKeyRef:
                      name: eca-system-db
                      key: username
                - name: PGPASSWORD
                  valueFrom:
                    secretKeyRef:
                      name: eca-system-db
                      key: password
              volumeMounts:
                - name: backup-storage
                  mountPath: /backup
          volumes:
            - name: backup-storage
              persistentVolumeClaim:
                claimName: eca-backup-pvc
```

---

## Восстановление (pg_restore)

**Важно:** восстановление требует downtime приложения или переключения трафика на реплику.

### Шаг 1. Остановить приложение

```bash
# Kubernetes
kubectl scale deployment eca-system-backend --replicas=0 -n eca

# Docker Compose
docker compose stop app
```

### Шаг 2. Подготовить чистую БД

```bash
# Вариант A: очистить схему существующей БД
psql -h $DB_HOST -U postgres -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO eca_user;"

# Вариант B: пересоздать БД (предпочтительно для полного восстановления)
psql -h $DB_HOST -U postgres -c "DROP DATABASE IF EXISTS eca_db;"
psql -h $DB_HOST -U postgres -c "CREATE DATABASE eca_db OWNER eca_user;"
```

### Шаг 3. Выполнить восстановление

```bash
export DB_HOST=prod-postgres.internal
export DB_PORT=5432
export DB_NAME=eca_db
export DB_USER=eca_user
export DB_PASSWORD=PROD_DB_PASSWORD

./ops/backup/pg_restore.sh /var/backups/eca/eca_backup_20260628_030000.dump
```

Флаги pg_restore: `--no-owner`, `--no-privileges`, `--exit-on-error` (прерывается при первой ошибке).

### Шаг 4. Запустить приложение

```bash
# Kubernetes
kubectl scale deployment eca-system-backend --replicas=3 -n eca
kubectl rollout status deployment/eca-system-backend -n eca

# Docker Compose
docker compose start app
```

---

## Point-in-Time Recovery (PITR)

PITR применяется когда нужно откатиться к конкретному моменту времени (например, до ошибочного удаления данных). Требует предварительно настроенного WAL-архивирования.

### Настройка WAL-архивирования (предварительная конфигурация)

В `postgresql.conf` целевого сервера:

```ini
wal_level = replica
archive_mode = on
archive_command = 'cp %p /var/lib/postgresql/wal_archive/%f'
# В prod заменить на rsync/s3cmd до отдельного хранилища
```

### Создание базового снимка (pg_basebackup)

```bash
# Выполнять регулярно (например, еженедельно)
pg_basebackup \
  -h $DB_HOST \
  -U replication_user \
  -D /var/backups/eca/basebackup_$(date +%Y%m%d) \
  -F tar \
  -z \
  --wal-method=stream \
  --checkpoint=fast \
  --progress
```

### Восстановление к точке во времени

```bash
# 1. Остановить PostgreSQL
systemctl stop postgresql

# 2. Восстановить базовый снимок
tar -xzf /var/backups/eca/basebackup_YYYYMMDD/base.tar.gz -C /var/lib/postgresql/16/main/
tar -xzf /var/backups/eca/basebackup_YYYYMMDD/pg_wal.tar.gz -C /var/lib/postgresql/16/main/pg_wal/

# 3. Создать файл recovery.conf (PostgreSQL 16: recovery_target_time в postgresql.conf)
cat > /var/lib/postgresql/16/main/postgresql.conf.recovery << 'EOF'
restore_command = 'cp /var/lib/postgresql/wal_archive/%f %p'
recovery_target_time = '2026-06-28 11:30:00'
recovery_target_action = 'promote'
EOF

# Добавить параметры в postgresql.conf
cat /var/lib/postgresql/16/main/postgresql.conf.recovery >> /var/lib/postgresql/16/main/postgresql.conf

# 4. Создать файл-маркер начала recovery
touch /var/lib/postgresql/16/main/recovery.signal

# 5. Запустить PostgreSQL — он применит WAL до target_time и promoted
systemctl start postgresql

# Следить за логами:
journalctl -u postgresql -f | grep -E "recovery|promote|LOG"
```

---

## Проверка консистентности после восстановления

### 1. Состояние Flyway-миграций

```sql
-- Все миграции должны иметь success = true
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;

-- Нет незавершённых
SELECT count(*) FROM flyway_schema_history WHERE success = false;
-- Ожидаемый результат: 0
```

### 2. Целостность ключевых таблиц

```sql
-- Количество последовательностей
SELECT count(*) FROM sequences;

-- Количество активных экземпляров
SELECT status, count(*) FROM execution_instances GROUP BY status;

-- Последние записи Event Log
SELECT created_at, event_type, tail_number
FROM tracking_event_log
ORDER BY created_at DESC
LIMIT 10;

-- Нет «подвисших» outbound сообщений без деполучения
SELECT status, count(*) FROM outbound_messages GROUP BY status;
```

### 3. Проверка партиций tracking_event_log

```sql
-- Список партиций
SELECT tablename,
       pg_size_pretty(pg_total_relation_size('public.' || tablename)) AS size
FROM pg_tables
WHERE tablename LIKE 'tracking_event_log_%'
ORDER BY tablename;

-- Партиций должно быть (RETENTION_TEL_MONTHS + RETENTION_CREATE_AHEAD_MONTHS) штук
```

### 4. Smoke test приложения

```bash
# Health check
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Ожидаемый: {"status":"UP"}

# Логин
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ADMIN_PASSWORD"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Список последовательностей
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/sequences | python3 -m json.tool
```

---

## Расписание и хранение

| Тип бэкапа | Периодичность | Хранение | Инструмент |
|-----------|:---:|:---:|---|
| Логический дамп (pg_dump) | Ежедневно в 02:00 | 14 дней (`RETENTION_DAYS=14`) | `pg_backup.sh` |
| Базовый снимок (pg_basebackup) | Еженедельно | 4 недели | Вручную или CronJob |
| WAL-архив | Непрерывно | До следующего basebackup + 1 неделя | `archive_command` |

Все дампы хранить на отдельном хосте или во внешнем хранилище — не на том же диске, что PostgreSQL.
