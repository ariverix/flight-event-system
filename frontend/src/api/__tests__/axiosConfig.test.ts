/**
 * Unit-тесты интерцептора 401 в axiosConfig.
 *
 * Баг (аудит 2026-09): на 401 интерсептор чистил только localStorage['jwt'],
 * но не персистентный Zustand authStore ('eca-auth'). После полной перезагрузки
 * страницы (window.location.href = '/login') store гидратируется из localStorage
 * со старым user'ом → isAuthenticated остаётся true → ProtectedRoute пускает
 * на защищённые роуты без токена → новый 401 на первом же запросе.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import api from '../axiosConfig';
import { useAuthStore, type AuthUser } from '../../store/authStore';

const TEST_USER: AuthUser = {
  token:    'stale-jwt',
  username: 'admin',
  role:     'ADMIN',
  fullName: 'Test Administrator',
};

// axios не даёт публичного API для вызова зарегистрированного response-интерсептора
// напрямую — используем internal `.handlers`, как и остальной код interceptors.use().
function getResponseErrorHandler(): (error: unknown) => Promise<never> {
  const interceptors = api.interceptors.response as unknown as {
    handlers: Array<{ rejected: (error: unknown) => Promise<never> } | null>;
  };
  const handler = interceptors.handlers.find((h) => h !== null);
  if (!handler) throw new Error('response error interceptor не зарегистрирован');
  return handler.rejected;
}

describe('axiosConfig — 401 response interceptor', () => {
  beforeEach(() => {
    localStorage.clear();
    useAuthStore.setState({ user: null });
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, href: '' },
    });
  });

  it('на 401 чистит и localStorage.jwt, и authStore.user', async () => {
    localStorage.setItem('jwt', TEST_USER.token);
    useAuthStore.getState().setUser(TEST_USER);

    const rejected = getResponseErrorHandler();
    await rejected({ response: { status: 401 } }).catch(() => {});

    expect(localStorage.getItem('jwt')).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('на 401 редиректит на /login', async () => {
    useAuthStore.getState().setUser(TEST_USER);

    const rejected = getResponseErrorHandler();
    await rejected({ response: { status: 401 } }).catch(() => {});

    expect(window.location.href).toBe('/login');
  });

  it('на не-401 ошибку authStore.user не трогает', async () => {
    useAuthStore.getState().setUser(TEST_USER);

    const rejected = getResponseErrorHandler();
    await rejected({ response: { status: 500 } }).catch(() => {});

    expect(useAuthStore.getState().user).toEqual(TEST_USER);
  });
});
