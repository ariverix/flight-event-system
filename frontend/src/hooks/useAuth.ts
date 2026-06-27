/**
 * useAuth — тонкий React-hook поверх authStore (Zustand).
 *
 * Сигнатура намеренно совпадает со старым hook-ом, чтобы не ломать
 * существующие компоненты (ProtectedRoute, AppLayout, LoginPage …).
 * Состояние теперь синглтон: все потребители видят одни данные реактивно.
 *
 * Миграция: P7-1. Следующий шаг: заменить прямые localStorage-чтения
 * в axiosConfig на store.getState().user?.token (P7-5).
 */
import { useCallback } from 'react';
import { authApi } from '../api/authApi';
import { useAuthStore, type AuthUser } from '../store/authStore';

export const useAuth = () => {
  const { user, setUser, clear } = useAuthStore();

  const login = useCallback(
    async (username: string, password: string) => {
      const response = await authApi.login({ username, password });

      // Синхронизируем JWT в localStorage — axios-интерсептор читает его напрямую
      localStorage.setItem('jwt', response.token);

      const authUser: AuthUser = {
        token:        response.token,
        refreshToken: response.refreshToken,
        username:     response.username,
        role:         response.role,
        fullName:     response.fullName,
      };
      setUser(authUser);
    },
    [setUser],
  );

  const logout = useCallback(() => {
    localStorage.removeItem('jwt');
    clear();
    window.location.href = '/login';
  }, [clear]);

  return {
    user,
    login,
    logout,
    /** С Zustand/persist гидратация синхронная → loading всегда false */
    loading: false,
    isAuthenticated: !!user,
    isAdmin: user?.role === 'ADMIN',
  };
};
