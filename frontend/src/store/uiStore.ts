/**
 * UI-store — клиентские настройки интерфейса.
 *
 * Образец второго среза на Zustand: тема и состояние сайдбара.
 * ThemeContext сохраняется для обратной совместимости в P7-1;
 * полная миграция context → store запланирована в P7-5.
 */
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface UiState {
  isDark: boolean;
  sidebarCollapsed: boolean;
  toggleTheme: () => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      isDark: true,
      sidebarCollapsed: false,
      toggleTheme: () => set((s) => ({ isDark: !s.isDark })),
      setSidebarCollapsed: (collapsed) => set({ sidebarCollapsed: collapsed }),
    }),
    {
      name: 'eca-ui',
      // Симметрично с authStore: персистим только данные, не функции
      partialize: (s) => ({ isDark: s.isDark, sidebarCollapsed: s.sidebarCollapsed }),
    },
  ),
);
