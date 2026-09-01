/**
 * Singleton axios instance + интерсепторы.
 *
 * Токен читается из localStorage напрямую — не из store — чтобы избежать
 * циклических зависимостей (store → axios → store); authStore.ts ни от чего
 * из api/ не зависит, поэтому импорт store только для clear() безопасен.
 * При переходе на refresh-flow (P7-5) интерсептор ответа добавит /auth/refresh.
 */
import axios from 'axios';
import { useAuthStore } from '../store/authStore';

// ── Расширение глобального window для dev-утилит ──────────────────────────────
declare global {
  interface Window {
    /** Dev-only: DebugOverlay подписывается через этот callback. */
    __ecaError?: (msg: string) => void;
  }
}

const api = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('jwt');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    if (import.meta.env.DEV) {
      console.log(
        `%c→ ${(config.method ?? 'GET').toUpperCase()} ${config.url}`,
        'color:#3b82f6;font-weight:bold',
      );
    }
    return config;
  },
  (error) => Promise.reject(error),
);

api.interceptors.response.use(
  (response) => {
    if (import.meta.env.DEV) {
      console.log(
        `%c← ${response.status} ${response.config.url}`,
        'color:#10b981;font-weight:bold',
      );
    }
    return response;
  },
  (error: unknown) => {
    const err = error as {
      response?: { status?: number; data?: { message?: string } };
      config?: { url?: string };
      message?: string;
    };
    const status = err.response?.status;
    const url    = err.config?.url;
    const msg    = err.response?.data?.message ?? err.message;

    if (import.meta.env.DEV) {
      console.error(`%c✗ ${status} ${url}: ${msg}`, 'color:#ef4444;font-weight:bold');
    }

    if (typeof window.__ecaError === 'function') {
      window.__ecaError(`API ${status}: ${url} — ${msg}`);
    }

    if (status === 401) {
      localStorage.removeItem('jwt');
      useAuthStore.getState().clear();
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

export default api;
