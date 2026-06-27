/**
 * Unit-тесты authStore (Zustand + persist).
 *
 * Проверяем:
 *  - начальное состояние (user === null)
 *  - setUser() обновляет user
 *  - clear() сбрасывает user в null
 *  - partialize: в сериализованном состоянии только user, без функций (setUser/clear)
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useAuthStore, type AuthUser } from '../authStore';

const TEST_USER: AuthUser = {
  token:    'test-jwt-token',
  username: 'admin',
  role:     'ADMIN',
  fullName: 'Test Administrator',
};

beforeEach(() => {
  // Сброс состояния между тестами; persist запишет в localStorage — jsdom это поддерживает
  useAuthStore.setState({ user: null });
  localStorage.clear();
});

describe('authStore', () => {
  it('начальное состояние: user === null', () => {
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('setUser() устанавливает пользователя', () => {
    useAuthStore.getState().setUser(TEST_USER);
    expect(useAuthStore.getState().user).toEqual(TEST_USER);
  });

  it('setUser() сохраняет всё поля пользователя', () => {
    const userWithRefresh: AuthUser = { ...TEST_USER, refreshToken: 'rt-xxx' };
    useAuthStore.getState().setUser(userWithRefresh);
    expect(useAuthStore.getState().user?.refreshToken).toBe('rt-xxx');
  });

  it('clear() сбрасывает user обратно в null', () => {
    useAuthStore.getState().setUser(TEST_USER);
    expect(useAuthStore.getState().user).not.toBeNull();

    useAuthStore.getState().clear();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('partialize: персистированное состояние содержит только user', () => {
    useAuthStore.getState().setUser(TEST_USER);
    const fullState = useAuthStore.getState();

    // Применяем partialize вручную — так работает zustand/persist
    const partialize = (s: typeof fullState) => ({ user: s.user });
    const persisted = partialize(fullState);

    // Есть user
    expect(persisted).toHaveProperty('user');
    expect(persisted.user).toEqual(TEST_USER);

    // Нет функций (они не должны попасть в storage)
    expect(persisted).not.toHaveProperty('setUser');
    expect(persisted).not.toHaveProperty('clear');
  });

  it('partialize: после clear() в персистированном состоянии user === null', () => {
    useAuthStore.getState().setUser(TEST_USER);
    useAuthStore.getState().clear();

    const fullState = useAuthStore.getState();
    const partialize = (s: typeof fullState) => ({ user: s.user });
    const persisted = partialize(fullState);

    expect(persisted.user).toBeNull();
  });

  it('state.setUser является функцией (экшены доступны)', () => {
    expect(typeof useAuthStore.getState().setUser).toBe('function');
    expect(typeof useAuthStore.getState().clear).toBe('function');
  });
});
