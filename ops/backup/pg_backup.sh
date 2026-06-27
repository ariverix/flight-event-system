#!/usr/bin/env sh
# ops/backup/pg_backup.sh — логический бэкап PostgreSQL через pg_dump (custom format).
#
# Использование:
#   export DB_HOST=localhost DB_PORT=5432 DB_NAME=eca_db DB_USER=eca_user DB_PASSWORD=eca_password
#   export BACKUP_DIR=/var/backups/eca   RETENTION_DAYS=7
#   ./ops/backup/pg_backup.sh
#
# Переменные окружения (все опциональны, есть дефолты для dev):
#   DB_HOST         — хост СУБД (default: localhost)
#   DB_PORT         — порт СУБД (default: 5432)
#   DB_NAME         — имя базы данных (default: eca_db)
#   DB_USER         — пользователь PostgreSQL (default: eca_user)
#   DB_PASSWORD     — пароль (default: из файла .pgpass; рекомендуется не передавать в env на проде)
#   BACKUP_DIR      — каталог хранения дампов (default: /var/backups/eca)
#   RETENTION_DAYS  — кол-во суток хранения дампов (default: 7)
#
# Формат файла: custom (-F c) — поддерживает параллельное восстановление, сжатие, выборочное
# восстановление отдельных объектов; читается только через pg_restore, не через psql.
# --no-owner          — при восстановлении не ставить владельца (совместимость с любым суперпользователем).
# --no-privileges     — не дампить GRANT/REVOKE (права задаются при деплое).
# Таймстамп в имени файла позволяет хранить множество дампов с политикой ротации.
#
# Импортозамещение: только нативные утилиты PostgreSQL (pg_dump, pg_restore).
# Совместимые лицензии: PostgreSQL License (аналог MIT).

set -euo pipefail

# ──────────────────────────────────────────────
# Параметры подключения
# ──────────────────────────────────────────────
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-eca_db}"
DB_USER="${DB_USER:-eca_user}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/eca}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

# ──────────────────────────────────────────────
# Проверки предусловий
# ──────────────────────────────────────────────
if ! command -v pg_dump >/dev/null 2>&1; then
    echo "ERROR: pg_dump не найден в PATH. Установите postgresql-client." >&2
    exit 1
fi

if [ -n "${DB_PASSWORD:-}" ]; then
    export PGPASSWORD="${DB_PASSWORD}"
fi

# Создать каталог бэкапов, если не существует
mkdir -p "${BACKUP_DIR}"

# ──────────────────────────────────────────────
# Формирование имени файла с таймстампом
# ──────────────────────────────────────────────
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
DUMP_FILE="${BACKUP_DIR}/eca_backup_${TIMESTAMP}.dump"
LOG_FILE="${BACKUP_DIR}/eca_backup_${TIMESTAMP}.log"

echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Начало бэкапа: ${DB_HOST}:${DB_PORT}/${DB_NAME} → ${DUMP_FILE}" | tee "${LOG_FILE}"

# ──────────────────────────────────────────────
# pg_dump — логический бэкап в custom format
# ──────────────────────────────────────────────
pg_dump \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --username="${DB_USER}" \
    --dbname="${DB_NAME}" \
    --format=custom \
    --no-owner \
    --no-privileges \
    --verbose \
    --file="${DUMP_FILE}" \
    2>>"${LOG_FILE}"

DUMP_SIZE="$(du -sh "${DUMP_FILE}" | cut -f1)"
echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Бэкап завершён: ${DUMP_FILE} (${DUMP_SIZE})" | tee -a "${LOG_FILE}"

# ──────────────────────────────────────────────
# Очистка старых дампов по retention
# ──────────────────────────────────────────────
echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Чистка дампов старше ${RETENTION_DAYS} суток в ${BACKUP_DIR}" | tee -a "${LOG_FILE}"
find "${BACKUP_DIR}" -name "eca_backup_*.dump" -mtime "+${RETENTION_DAYS}" -print -delete 2>>"${LOG_FILE}" || true
find "${BACKUP_DIR}" -name "eca_backup_*.log"  -mtime "+${RETENTION_DAYS}" -print -delete 2>>"${LOG_FILE}" || true

echo "$(date '+%Y-%m-%d %H:%M:%S') [INFO] Ротация завершена." | tee -a "${LOG_FILE}"

echo "${DUMP_FILE}"
