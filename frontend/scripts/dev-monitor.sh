#!/usr/bin/env bash
# scripts/dev-monitor.sh
# Запускает dev сервер и мониторит все изменения

echo "════════════════════════════════════════"
echo "  ECA SYSTEM — LIVE MONITOR"
echo "════════════════════════════════════════"

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_FILE="/tmp/eca-dev-$(date +%Y%m%d-%H%M%S).log"

echo "📁 Проект: $PROJECT_DIR"
echo "📝 Лог: $LOG_FILE"
echo ""

check_ts() {
  local errors
  errors=$(cd "$PROJECT_DIR" && npx tsc --noEmit 2>&1 | grep -c "error TS" || true)
  if [ "$errors" -eq 0 ]; then
    echo "✅ TypeScript: OK (0 ошибок)"
  else
    echo "❌ TypeScript: $errors ошибок:"
    cd "$PROJECT_DIR" && npx tsc --noEmit 2>&1 | grep "error TS" | head -10
  fi
}

check_server() {
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173 2>/dev/null)
  if [ "$status" = "200" ]; then
    echo "✅ Dev сервер: http://localhost:5173 (HTTP $status)"
  else
    echo "⚠️  Dev сервер: HTTP $status"
  fi
}

watch_errors() {
  echo ""
  echo "🔍 Слежу за ошибками... (Ctrl+C для остановки)"
  echo "════════════════════════════════════════"
  tail -f "$LOG_FILE" | while IFS= read -r line; do
    if echo "$line" | grep -qiE "error|ERROR|Error"; then
      echo "❌ $line"
    elif echo "$line" | grep -qiE "warn|WARN|Warning"; then
      echo "⚠️  $line"
    elif echo "$line" | grep -qiE "ready|compiled|done|built"; then
      echo "✅ $line"
    fi
  done
}

cd "$PROJECT_DIR"

echo "🚀 Запускаю dev сервер..."
npm run dev > "$LOG_FILE" 2>&1 &
DEV_PID=$!
echo "   PID: $DEV_PID"

echo "⏳ Жду запуска (до 20 сек)..."
for i in $(seq 1 20); do
  sleep 1
  status=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173 2>/dev/null)
  if [ "$status" = "200" ]; then
    echo "✅ Сервер запущен за ${i} сек"
    break
  fi
  echo "   ${i}/20..."
done

echo ""
echo "═══════ НАЧАЛЬНАЯ ПРОВЕРКА ═══════"
check_server
check_ts
echo "══════════════════════════════════"
echo ""

# Слежка за файлами (Linux/macOS)
if command -v inotifywait &> /dev/null; then
  inotifywait -m -r "$PROJECT_DIR/src" \
    --event modify --event create \
    --format '%T %e %f' --timefmt '%H:%M:%S' \
    2>/dev/null | while read -r line; do
    echo "📝 $line"
    if echo "$line" | grep -qE "\.tsx?"; then
      sleep 1
      check_ts
    fi
  done &
elif command -v fswatch &> /dev/null; then
  fswatch -r "$PROJECT_DIR/src" | while read -r file; do
    echo "📝 $(basename "$file")"
    if echo "$file" | grep -qE "\.tsx?"; then
      sleep 1
      check_ts
    fi
  done &
else
  echo "ℹ️  Для слежки за файлами: apt install inotify-tools (Linux) / brew install fswatch (macOS)"
fi

trap "kill $DEV_PID 2>/dev/null; echo 'Сервер остановлен'" EXIT
watch_errors
