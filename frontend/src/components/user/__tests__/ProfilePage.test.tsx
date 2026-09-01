import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { ProfilePage } from '../ProfilePage';
import { authApi } from '../../../api/authApi';
import type { UserResponse } from '../../../types/auth';

vi.mock('../../../api/authApi', () => ({
  authApi: { me: vi.fn() },
}));

const { notifyError } = vi.hoisted(() => ({ notifyError: vi.fn() }));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ error: notifyError }),
}));

const mockedMe = vi.mocked(authApi.me);

// jsdom не реализует matchMedia, а antd Descriptions/Row его требует
window.matchMedia = ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addListener: () => {},
  removeListener: () => {},
  addEventListener: () => {},
  removeEventListener: () => {},
  dispatchEvent: () => false,
})) as typeof window.matchMedia;

const PROFILE: UserResponse = {
  id:        1,
  username:  'admin',
  fullName:  'Иван Иванов',
  role:      'ADMIN',
  enabled:   true,
  createdAt: '2026-01-15T10:00:00',
};

beforeEach(() => {
  mockedMe.mockReset();
  notifyError.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('ProfilePage', () => {
  it('после загрузки рендерит имя, роль и статус из словаря (без хардкода)', async () => {
    mockedMe.mockResolvedValueOnce(PROFILE);
    render(<ProfilePage />);

    await waitFor(() => expect(screen.getAllByText('Иван Иванов').length).toBeGreaterThan(0));

    expect(screen.getByText('Профиль пользователя')).toBeTruthy();
    expect(screen.getByText('Учётные данные')).toBeTruthy();
    expect(screen.getByText('Активен')).toBeTruthy();
    expect(screen.getAllByText('Администратор').length).toBeGreaterThan(0);
  });

  it('неактивный пользователь: показывает статус «Отключён»', async () => {
    mockedMe.mockResolvedValueOnce({ ...PROFILE, enabled: false });
    render(<ProfilePage />);

    await waitFor(() => expect(screen.getByText('Отключён')).toBeTruthy());
  });

  it('ошибка загрузки: показывает нотификацию с дефолтным сообщением из словаря', async () => {
    mockedMe.mockRejectedValueOnce({ response: undefined, message: 'Network Error' });
    render(<ProfilePage />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки профиля',
        description: 'Network Error',
      }),
    );
  });
});
