import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { ExecutionDetail } from '../ExecutionDetail';
import { executionApi } from '../../../api/executionApi';
import { sequenceApi } from '../../../api/sequenceApi';
import type { ExecutionInstanceResponse, StepExecutionResponse } from '../../../types/execution';
import type { SequenceResponse } from '../../../types/sequence';

vi.mock('../../../api/executionApi', () => ({
  executionApi: { getExecutionById: vi.fn() },
}));
vi.mock('../../../api/sequenceApi', () => ({
  sequenceApi: { getSequenceById: vi.fn() },
}));

const { notifyError, navigate } = vi.hoisted(() => ({
  notifyError: vi.fn(),
  navigate:    vi.fn(),
}));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ error: notifyError, success: vi.fn() }),
}));
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
  useParams:   () => ({ id: '7' }),
}));

// ExecutionFlow рендерит React Flow — не имеет смысла гонять его в юнит-тесте
// этой страницы (нет собственной логики завязанной на i18n), мокаем целиком.
vi.mock('../ExecutionFlow', () => ({
  ExecutionFlow: () => <div data-testid="execution-flow-stub" />,
}));

// jsdom не реализует matchMedia, а antd Descriptions/Row его требуют
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

const mockedGetExecutionById = vi.mocked(executionApi.getExecutionById);
const mockedGetSequenceById  = vi.mocked(sequenceApi.getSequenceById);

function seqOf(overrides: Partial<SequenceResponse> = {}): SequenceResponse {
  return {
    id: 10, name: 'Проверка после взлёта', description: '',
    status: 'ACTIVE', startCriteriaJson: null, stopCriteriaJson: null,
    createdAt: '2026-01-15T09:00:00', updatedAt: '2026-01-15T09:00:00',
    steps: [],
    ...overrides,
  };
}

function stepExecOf(overrides: Partial<StepExecutionResponse> = {}): StepExecutionResponse {
  return {
    id: 1, stepIndex: 0, stepType: 'ACTION', result: 'SUCCESS',
    transitionAction: 'CONTINUE', transitionTarget: null,
    executedAt: '2026-01-15T10:00:05', detailsJson: null,
    ...overrides,
  };
}

function execOf(overrides: Partial<ExecutionInstanceResponse> = {}): ExecutionInstanceResponse {
  return {
    id: 7, sequenceId: 10, sequenceName: 'Проверка после взлёта',
    aircraftId: 'VP-BQR', flightNumber: 'SU1234', status: 'COMPLETED',
    currentStepIndex: null, contextJson: '{}',
    startedAt: '2026-01-15T10:00:00', completedAt: '2026-01-15T10:05:00',
    stepExecutions: [],
    ...overrides,
  };
}

beforeEach(() => {
  mockedGetExecutionById.mockReset();
  mockedGetSequenceById.mockReset();
  notifyError.mockReset();
  navigate.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('ExecutionDetail', () => {
  it('рендерит статус и лейблы из словаря (без хардкода)', async () => {
    mockedGetExecutionById.mockResolvedValueOnce(execOf());
    mockedGetSequenceById.mockResolvedValueOnce(seqOf());
    render(<ExecutionDetail />);

    await waitFor(() => expect(screen.getByText('Детали выполнения')).toBeTruthy());

    expect(screen.getByText('Завершено')).toBeTruthy();
    expect(screen.getByText('Назад к выполнениям')).toBeTruthy();
    expect(screen.getByText('Визуальный прогресс')).toBeTruthy();
  });

  it('без завершения: показывает «В процессе»', async () => {
    mockedGetExecutionById.mockResolvedValueOnce(execOf({ status: 'RUNNING', completedAt: null }));
    mockedGetSequenceById.mockResolvedValueOnce(seqOf());
    render(<ExecutionDetail />);

    await waitFor(() => expect(screen.getByText('В процессе')).toBeTruthy());
  });

  it('без шагов: показывает «Шаги ещё не выполнялись»', async () => {
    mockedGetExecutionById.mockResolvedValueOnce(execOf({ stepExecutions: [] }));
    mockedGetSequenceById.mockResolvedValueOnce(seqOf());
    render(<ExecutionDetail />);

    await waitFor(() => expect(screen.getByText('Шаги ещё не выполнялись')).toBeTruthy());
  });

  it('шаг с результатом SUCCESS и transition CONTINUE: рендерит переведённые лейблы', async () => {
    mockedGetExecutionById.mockResolvedValueOnce(
      execOf({ stepExecutions: [stepExecOf()] }),
    );
    mockedGetSequenceById.mockResolvedValueOnce(seqOf());
    render(<ExecutionDetail />);

    await waitFor(() => expect(screen.getByText('Успех')).toBeTruthy());
    expect(screen.getByText('Действие')).toBeTruthy();
    expect(screen.getByText(/Продолжить/)).toBeTruthy();
  });

  it('ошибка загрузки: показывает нотификацию из словаря и редиректит', async () => {
    mockedGetExecutionById.mockRejectedValueOnce({ response: undefined, message: 'Network Error' });
    render(<ExecutionDetail />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки деталей выполнения',
        description: 'Network Error',
      }),
    );
    expect(navigate).toHaveBeenCalledWith('/executions');
  });
});
