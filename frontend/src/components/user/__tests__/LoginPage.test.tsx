import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginPage } from '../LoginPage';

const { login, navigate, notifySuccess, notifyError } = vi.hoisted(() => ({
  login: vi.fn(),
  navigate: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));

vi.mock('../../../hooks/useAuth', () => ({
  useAuth: () => ({ login }),
}));

vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ success: notifySuccess, error: notifyError }),
}));

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}));

// jsdom не реализует matchMedia, а antd Row/useBreakpoint его требует
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

beforeEach(() => {
  login.mockReset();
  navigate.mockReset();
  notifySuccess.mockReset();
  notifyError.mockReset();
});

// vite.config: globals=false → авто-cleanup @testing-library не активен, чистим вручную
afterEach(() => {
  cleanup();
});

describe('LoginPage', () => {
  it('рендерит заголовок, подписи полей и кнопку из словаря (без хардкода)', () => {
    render(<LoginPage />);

    expect(screen.getByText('СИСТЕМА ЕСА')).toBeTruthy();
    expect(screen.getByText('Управление последовательностями событий ВС')).toBeTruthy();
    expect(screen.getByPlaceholderText('Имя пользователя')).toBeTruthy();
    expect(screen.getByPlaceholderText('Пароль')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Войти в систему' })).toBeTruthy();
  });

  it('успешный вход: зовёт login(), показывает success-нотификацию, редиректит на /', async () => {
    login.mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.type(screen.getByPlaceholderText('Имя пользователя'), 'admin');
    await user.type(screen.getByPlaceholderText('Пароль'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Войти в систему' }));

    await waitFor(() => expect(login).toHaveBeenCalledWith('admin', 'secret'));
    expect(notifySuccess).toHaveBeenCalledWith({
      message: 'Вход выполнен',
      description: 'Добро пожаловать в Систему ЕСА!',
    });
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('ошибка входа без ответа сервера: показывает дефолтное сообщение из словаря', async () => {
    login.mockRejectedValueOnce({ response: undefined });
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.type(screen.getByPlaceholderText('Имя пользователя'), 'admin');
    await user.type(screen.getByPlaceholderText('Пароль'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Войти в систему' }));

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message: 'Ошибка входа',
        description: 'Неверное имя пользователя или пароль',
      }),
    );
    expect(navigate).not.toHaveBeenCalled();
  });
});
