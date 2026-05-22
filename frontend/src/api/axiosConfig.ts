import axios from 'axios';

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
  (error) => {
    const status = error.response?.status;
    const url    = error.config?.url;
    const msg    = error.response?.data?.message ?? error.message;

    if (import.meta.env.DEV) {
      console.error(`%c✗ ${status} ${url}: ${msg}`, 'color:#ef4444;font-weight:bold');
    }

    // Передаём в DebugOverlay если инициализирован
    if (typeof (window as any).__ecaError === 'function') {
      (window as any).__ecaError(`API ${status}: ${url} — ${msg}`);
    }

    if (status === 401) {
      localStorage.removeItem('jwt');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

export default api;
