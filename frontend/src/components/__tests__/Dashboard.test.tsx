import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { Dashboard } from '../Dashboard';
import { sequenceApi } from '../../api/sequenceApi';
import { executionApi } from '../../api/executionApi';
import { messageApi } from '../../api/messageApi';
import type { ExecutionInstanceResponse } from '../../types/execution';
import type { PageResponse } from '../../types/sequence';

vi.mock('../../api/sequenceApi', () => ({
  sequenceApi: { getSequences: vi.fn() },
}));
vi.mock('../../api/executionApi', () => ({
  executionApi: { getExecutions: vi.fn() },
}));
vi.mock('../../api/messageApi', () => ({
  messageApi: { getMessages: vi.fn() },
}));

const { navigate } = vi.hoisted(() => ({ navigate: vi.fn() }));
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}));
vi.mock('../../hooks/useAuth', () => ({
  useAuth: () => ({ user: { fullName: 'Иван Иванов', role: 'ADMIN' } }),
}));

// jsdom не реализует matchMedia, а antd Row/Col его требуют
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

const mockedGetSequences  = vi.mocked(sequenceApi.getSequences);
const mockedGetExecutions = vi.mocked(executionApi.getExecutions);
const mockedGetMessages   = vi.mocked(messageApi.getMessages);

function pageOf<T>(content: T[], totalElements = content.length): PageResponse<T> {
  return { content, totalElements, totalPages: 1, size: 100, number: 0 };
}

function execOf(overrides: Partial<ExecutionInstanceResponse> = {}): ExecutionInstanceResponse {
  return {
    id: 1, sequenceId: 1, sequenceName: 'Проверка после взлёта',
    aircraftId: 'VP-BQR', flightNumber: 'SU1234', status: 'RUNNING',
    currentStepIndex: 1, contextJson: '{}', startedAt: new Date().toISOString(),
    completedAt: null, stepExecutions: [],
    ...overrides,
  } as ExecutionInstanceResponse;
}

beforeEach(() => {
  mockedGetSequences.mockReset();
  mockedGetExecutions.mockReset();
  mockedGetMessages.mockReset();
  navigate.mockReset();

  mockedGetSequences.mockResolvedValue(pageOf([], 3));
  mockedGetExecutions.mockResolvedValue(pageOf([execOf()], 1));
  mockedGetMessages.mockResolvedValue(pageOf([], 10));
});

afterEach(() => {
  cleanup();
});

describe('Dashboard', () => {
  it('рендерит заголовок и карточки статистики из словаря (без хардкода)', async () => {
    render(<Dashboard />);

    await waitFor(() => expect(screen.getByText('Проверка после взлёта')).toBeTruthy());

    expect(screen.getByText('Панель управления')).toBeTruthy();
    expect(screen.getByText('Всего сценариев')).toBeTruthy();
    expect(screen.getByText('Быстрые действия')).toBeTruthy();
    expect(screen.getByText('Статус системы')).toBeTruthy();
  });

  it('рендерит статус выполнения из общего словаря instanceStatuses', async () => {
    render(<Dashboard />);

    await waitFor(() => expect(screen.getByText('Выполняется')).toBeTruthy());
  });

  it('без недавних выполнений: показывает пустой список из словаря', async () => {
    mockedGetExecutions.mockReset();
    mockedGetExecutions.mockResolvedValue(pageOf([], 0));
    render(<Dashboard />);

    await waitFor(() => expect(screen.getByText('Выполнений ещё не было')).toBeTruthy());
  });
});
