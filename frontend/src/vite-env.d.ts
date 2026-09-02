/// <reference types="vite/client" />

/**
 * Runtime-конфигурация, генерируемая `entrypoint.sh` (Docker Compose) или монтируемая
 * ConfigMap'ом (K8s) в `env.js`, загружаемый до бандла приложения — см. `resolveWsUrl.ts`.
 */
interface RuntimeEnv {
  VITE_WS_URL?: string;
}

interface Window {
  __env__?: RuntimeEnv;
}
