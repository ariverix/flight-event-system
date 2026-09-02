/**
 * Резолвит URL WebSocket-эндпоинта ECA (P7-4).
 *
 * Приоритет:
 *  1. `window.__env__.VITE_WS_URL` — runtime-override, генерируется `entrypoint.sh` /
 *     монтируется ConfigMap'ом в K8s (см. `frontend/Dockerfile`).
 *  2. `import.meta.env.VITE_WS_URL` — build-time override для локальной разработки
 *     (`.env.local`), где Vite dev-сервер и backend слушают разные порты.
 *  3. Иначе — из текущего `window.location`: `ws(s)://<host>/ws/eca`. Работает без какой-либо
 *     конфигурации в docker-compose и типовой K8s-топологии, где фронтенд и бэкенд отдаются
 *     с одного хоста — именно этот путь раньше не имел вообще никакого способа задать URL
 *     (ни ARG в корневом `Dockerfile`, ни `window.__env__`, который никто не читал).
 *
 * В отличие от прежней реализации, результат всегда непустая строка — «нет-оп без URL»
 * для браузерного рантайма ушёл вместе с гарантированным fallback-путём 3.
 */
export function resolveWsUrl(): string {
  const runtimeOverride = window.__env__?.VITE_WS_URL;
  if (runtimeOverride) return runtimeOverride;

  const buildTimeOverride = import.meta.env['VITE_WS_URL'] as string | undefined;
  if (buildTimeOverride) return buildTimeOverride;

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws/eca`;
}
