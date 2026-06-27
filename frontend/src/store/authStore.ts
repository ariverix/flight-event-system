/**
 * Auth store — singleton источник истины для состояния аутентификации.
 *
 * Выбор: Zustand с persist-middleware (localStorage, ключ «eca-auth»).
 * JWT для axios-интерсептора дополнительно хранится под ключом «jwt» — это
 * осознанное решение: интерсептор читает токен напрямую из storage без
 * импорта store, что исключает циклические зависимости.
 *
 * ADR: docs/adr/ADR-0005-frontend-architecture.md
 */
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface AuthUser {
  token: string;
  /** Refresh-token (P4-2). Может отсутствовать у старых сессий. */
  refreshToken?: string;
  username: string;
  role: 'OPERATOR' | 'ADMIN';
  fullName: string;
}

interface AuthState {
  /** null — пользователь не аутентифицирован. */
  user: AuthUser | null;
  setUser: (user: AuthUser | null) => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      setUser: (user) => set({ user }),
      clear: () => set({ user: null }),
    }),
    {
      name: 'eca-auth',
      // Персистим только user; транзитные UI-флаги не трогаем
      partialize: (state) => ({ user: state.user }),
    },
  ),
);
