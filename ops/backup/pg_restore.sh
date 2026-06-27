#!/usr/bin/env sh
# ops/backup/pg_restore.sh — восстановление базы ECA из custom-формата pg_dump.
#
# Использование:
#   export DB_HOST=localhost DB_PORT=5432 DB_NAME=eca_db DB_USER=eca_user DB_PASSWORD=eca_password
#   ./ops/backup/pg_restore.sh /var/backups/eca/eca_backup_20260101_030000.dump
#
# Аргументы:
#   $1  — путь к файлу дампа (.dump, custom format из pg_backup.sh)
#
# Переменные окружения:
#   DB_HOST       — хост СУБД (default: localhost)
#   DB_PORT       — порт СУБД (default: 5432)
#   DB_NAME       — имя базы данных для восстановления (default: eca_db)
#   DB_USER       — пользователь PostgreSQL (default: eca_user)
#   DB_PASSWORD   — пароль (default: из .pgpass)
#
# ВАЖНО: целевая БД должна существовать и быть ПУСТОЙ (без таблиц ECA).
# Если восстанавливаете поверх существующей схемы — сначала выполните:
#   psql -h $DB_HOST -U $DB_USER -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" $DB_NAME
# Или создайте чистую БД заранее:
#   createdb -h $DB_HOST -U $DB_USER -O $DB_USER $DB_NAME
#
# Импортозамещение: только нативные утилиты PostgreSQL.

set -euo pipefail

# ──────────────────────────────────────────────
# Аргументы
# ──────────────────────────────────────────────
if [ "$#" -ne 1 ]; then
    echo "Использование: $0 <путь_к_дампу.dump>" >&2
    echo "  Пример: $0 /var/backups/eca/eca_backup_20260101_030000.dump" >&2
    exit 1
fi

DUMP_FILE="$1"

if [ ! -f "${DUMP_FILE}" ]; then
    echo "ERROR: файл дампа не найден: ${DUMP_FILE}" >&2
    exit 1
fi

# ──────────────────────────────────────────────
# Параметры подключения
# ──────────────────────────────────────────────
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-eca_db}"
DB_USER="${DB_USER:-eca_user}"

# ──────────────────────────────────────────────
# Проверки предусловий
# ──────────────────────────────────────────────
if ! command -v pg_restore >/dev/null 2>&1; then
    echo "ERROR: pg_restore не найден в PATH. Установите postgresql-client." >&2
    exit 1
fi

if [ -n "${DB_PASSWORD:-}" ]; then
    export PGPASSWORD="${DB_PASSWORD}"
fi

echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Восстановление: ${DUMP_FILE} → ${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "$(date '+%Y-%m-%d %H:%M:%S') [WARN] Убедитесь, что целевая БД '${DB_NAME}' существует и пуста."

# ──────────────────────────────────────────────
# pg_restore — восстановление из custom-дампа
# --no-owner       — не восстанавливать владельца объектов
# --no-privileges  — не восстанавливать GRANT/REVOKE
# --exit-on-error  — прерваться при первой ошибке (по умолчанию pg_restore продолжает)
# ──────────────────────────────────────────────
pg_restore \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --username="${DB_USER}" \
    --dbname="${DB_NAME}" \
    --no-owner \
    --no-privileges \
    --exit-on-error \
    --verbose \
    "${DUMP_FILE}"

echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Восстановление завершено успешно."
echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Рекомендуется проверить: SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
