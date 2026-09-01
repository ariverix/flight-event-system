import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { UserManagement } from '../UserManagement';
import { authApi } from '../../../api/authApi';
import type { UserResponse } from '../../../types/auth';

vi.mock('../../../api/authApi', () => ({
  authApi: { getUsers: vi.fn(), toggleUser: vi.fn(), register: vi.fn() },
}));

const { notifySuccess, notifyError } = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
}));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ success: notifySuccess, error: notifyError }),
}));

vi.mock('../../../hooks/useAuth', () => ({
  useAuth: () => ({ user: { username: 'admin', role: 'ADMIN' } }),
}));

const mockedGetUsers   = vi.mocked(authApi.getUsers);
const mockedToggleUser = vi.mocked(authApi.toggleUser);
const mockedRegister   = vi.mocked(authApi.register);

// jsdom не реализует matchMedia, а antd Table/Modal его требуют
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

function userOf(overrides: Partial<UserResponse> = {}): UserResponse {
  return {
    id:        1,
    username:  'operator1',
    fullName:  'Пётр Петров',
    role:      'OPERATOR',
    enabled:   true,
    createdAt: '2026-01-15T10:00:00',
    ...overrides,
  };
}

beforeEach(() => {
  mockedGetUsers.mockReset();
  mockedToggleUser.mockReset();
  mockedRegister.mockReset();
  notifySuccess.mockReset();
  notifyError.mockReset();
});

afterEach(() => {
  cleanup();
});

/** Клик по опции открытого дропдауна AntD (визуальный узел .ant-select-item-option-content). */
function clickDropdownOption(text: RegExp): void {
  const contents = Array.from(document.querySelectorAll('.ant-select-item-option-content'));
  const target = contents.find((el) => text.test(el.textContent ?? ''));
  expect(target, `опция дропдауна ${text} не найдена`).toBeTruthy();
  fireEvent.click(target!);
}

describe('UserManagement', () => {
  it('рендерит таблицу с ролью и статусом из словаря (без хардкода)', async () => {
    mockedGetUsers.mockResolvedValueOnce([userOf()]);
    render(<UserManagement />);

    await waitFor(() => expect(screen.getByText('operator1')).toBeTruthy());

    expect(screen.getByText('Оператор')).toBeTruthy();
    expect(screen.getByText('Управление пользователями')).toBeTruthy();
  });

  it('переключение статуса: зовёт toggleUser и показывает success-нотификацию', async () => {
    mockedGetUsers.mockResolvedValue([userOf({ id: 2, username: 'operator2' })]);
    mockedToggleUser.mockResolvedValueOnce(userOf({ id: 2, enabled: false }));
    const user = userEvent.setup();
    render(<UserManagement />);

    await waitFor(() => expect(screen.getByText('operator2')).toBeTruthy());

    const toggle = screen.getByRole('switch');
    await user.click(toggle);

    await waitFor(() => expect(mockedToggleUser).toHaveBeenCalledWith(2));
    expect(notifySuccess).toHaveBeenCalledWith({ message: 'Статус пользователя обновлён' });
  });

  it('ошибка загрузки списка: показывает нотификацию из словаря', async () => {
    mockedGetUsers.mockRejectedValueOnce({ response: undefined, message: 'Network Error' });
    render(<UserManagement />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки пользователей',
        description: 'Network Error',
      }),
    );
  });

  it('создание пользователя: открывает модалку, отправляет форму, зовёт register()', async () => {
    mockedGetUsers.mockResolvedValue([]);
    mockedRegister.mockResolvedValueOnce(userOf({ id: 3, username: 'newuser' }));
    const user = userEvent.setup();
    render(<UserManagement />);

    await waitFor(() => expect(mockedGetUsers).toHaveBeenCalled());

    await user.click(screen.getByRole('button', { name: /Добавить пользователя/ }));

    await user.type(screen.getByLabelText('Логин'), 'newuser');
    await user.type(screen.getByLabelText('Пароль'), 'secret1');
    await user.type(screen.getByLabelText('Полное имя'), 'Новый Пользователь');

    await user.click(screen.getByLabelText('Роль'));
    clickDropdownOption(/Оператор/);

    await user.click(screen.getByRole('button', { name: 'Создать пользователя' }));

    await waitFor(() =>
      expect(mockedRegister).toHaveBeenCalledWith(
        expect.objectContaining({ username: 'newuser', password: 'secret1', fullName: 'Новый Пользователь' }),
      ),
    );
    expect(notifySuccess).toHaveBeenCalledWith({ message: 'Пользователь создан успешно' });
  });
});
