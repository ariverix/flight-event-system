import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { ExecutionList } from '../ExecutionList';
import { executionApi } from '../../../api/executionApi';
import type { ExecutionInstanceResponse } from '../../../types/execution';
import type { PageResponse } from '../../../types/sequence';

vi.mock('../../../api/executionApi', () => ({
  executionApi: { getExecutions: vi.fn() },
}));

const { notifyError, navigate } = vi.hoisted(() => ({
  notifyError: vi.fn(),
  navigate: vi.fn(),
}));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ error: notifyError, success: vi.fn() }),
}));
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}));

const mockedGetExecutions = vi.mocked(executionApi.getExecutions);

// jsdom не реализует matchMedia, а antd Table/Select его требуют
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

function execOf(overrides: Partial<ExecutionInstanceResponse> = {}): ExecutionInstanceResponse {
  return {
    id:               1,
    sequenceId:       10,
    sequenceName:     'Проверка после взлёта',
    aircraftId:       'VP-BQR',
    flightNumber:     'SU1234',
    status:           'RUNNING',
    currentStepIndex: 2,
    startedAt:        '2026-01-15T10:00:00',
    completedAt:      null,
    ...overrides,
  } as ExecutionInstanceResponse;
}

function pageOf(content: ExecutionInstanceResponse[]): PageResponse<ExecutionInstanceResponse> {
  return { content, totalElements: content.length, totalPages: 1, size: 10, number: 0 };
}

beforeEach(() => {
  mockedGetExecutions.mockReset();
  notifyError.mockReset();
  navigate.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('ExecutionList', () => {
  it('рендерит статус и заголовок из словаря (без хардкода)', async () => {
    mockedGetExecutions.mockResolvedValue(pageOf([execOf()]));
    render(<ExecutionList />);

    await waitFor(() => expect(screen.getByText('Проверка после взлёта')).toBeTruthy());

    expect(screen.getByText('Экземпляры выполнений')).toBeTruthy();
    expect(screen.getAllByText('Выполняется').length).toBeGreaterThan(0);
  });

  it('завершённое выполнение без completedAt: показывает «В процессе» только для незавершённых', async () => {
    mockedGetExecutions.mockResolvedValue(
      pageOf([execOf({ id: 2, status: 'WAITING', completedAt: null })]),
    );
    render(<ExecutionList />);

    await waitFor(() => expect(screen.getByText('В процессе')).toBeTruthy());
  });

  it('ошибка загрузки: показывает нотификацию из словаря', async () => {
    mockedGetExecutions.mockRejectedValue({ response: undefined, message: 'Network Error' });
    render(<ExecutionList />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки выполнений',
        description: 'Network Error',
      }),
    );
  });

  it('без выполнений: показывает пустое состояние из словаря', async () => {
    mockedGetExecutions.mockResolvedValue(pageOf([]));
    render(<ExecutionList />);

    await waitFor(() => expect(screen.getByText('Выполнений нет')).toBeTruthy());
    expect(screen.getByText('Запустите сценарий через Симулятор или Демонстрацию')).toBeTruthy();
  });
});
