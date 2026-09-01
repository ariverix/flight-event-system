import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SequenceForm } from '../SequenceForm';
import { sequenceApi } from '../../../api/sequenceApi';
import type { SequenceResponse } from '../../../types/sequence';

vi.mock('../../../api/sequenceApi', () => ({
  sequenceApi: {
    getSequenceById: vi.fn(),
    createSequence:  vi.fn(),
    updateSequence:  vi.fn(),
    deleteStep:      vi.fn(),
  },
}));

const { notifySuccess, notifyError, navigate } = vi.hoisted(() => ({
  notifySuccess: vi.fn(),
  notifyError:   vi.fn(),
  navigate:      vi.fn(),
}));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
  useParams:   () => ({ id: 'new' }),
}));
vi.mock('../../../hooks/useAuth', () => ({
  useAuth: () => ({ isAdmin: true }),
}));

// CriteriaEditor/StepForm/SequenceFlow — самостоятельные компоненты без i18n
// в этом файле; мокаем, чтобы тест фокусировался на SequenceForm.
vi.mock('../CriteriaEditor', () => ({
  CriteriaEditor: () => <div data-testid="criteria-editor-stub" />,
}));
vi.mock('../StepForm', () => ({
  StepForm: () => <div data-testid="step-form-stub" />,
}));
vi.mock('../SequenceFlow', () => ({
  SequenceFlow: () => <div data-testid="sequence-flow-stub" />,
}));

// jsdom не реализует matchMedia, а antd Form/Row его требуют
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

const mockedGetSequenceById = vi.mocked(sequenceApi.getSequenceById);
const mockedCreateSequence  = vi.mocked(sequenceApi.createSequence);

function seqOf(overrides: Partial<SequenceResponse> = {}): SequenceResponse {
  return {
    id: 1, name: 'Проверка после взлёта', description: 'Тест',
    status: 'DRAFT', startCriteriaJson: null, stopCriteriaJson: null,
    createdAt: '2026-01-15T09:00:00', updatedAt: '2026-01-15T09:00:00',
    steps: [],
    ...overrides,
  };
}

beforeEach(() => {
  mockedGetSequenceById.mockReset();
  mockedCreateSequence.mockReset();
  notifySuccess.mockReset();
  notifyError.mockReset();
  navigate.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('SequenceForm', () => {
  it('режим создания: рендерит заголовок и лейблы из словаря (без хардкода)', () => {
    render(<SequenceForm />);

    expect(screen.getAllByText('Создать последовательность').length).toBeGreaterThan(0);
    expect(screen.getByText('Название последовательности')).toBeTruthy();
    expect(screen.getByText('Критерий запуска (JSON)')).toBeTruthy();
  });

  it('успешное создание: зовёт createSequence(), показывает success-нотификацию', async () => {
    mockedCreateSequence.mockResolvedValueOnce(seqOf({ id: 42 }));
    const user = userEvent.setup();
    render(<SequenceForm />);

    await user.type(screen.getByLabelText('Название последовательности'), 'Новая последовательность');
    await user.type(screen.getByLabelText('Описание'), 'Описание теста');
    await user.click(screen.getByRole('button', { name: 'Создать последовательность' }));

    await waitFor(() => expect(mockedCreateSequence).toHaveBeenCalled());
    expect(notifySuccess).toHaveBeenCalledWith({ message: 'Последовательность создана' });
    expect(navigate).toHaveBeenCalledWith('/sequences/42');
  });

  it('ошибка сохранения: показывает нотификацию из словаря', async () => {
    mockedCreateSequence.mockRejectedValueOnce({ response: undefined, message: 'Network Error' });
    const user = userEvent.setup();
    render(<SequenceForm />);

    await user.type(screen.getByLabelText('Название последовательности'), 'X');
    await user.type(screen.getByLabelText('Описание'), 'Y');
    await user.click(screen.getByRole('button', { name: 'Создать последовательность' }));

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка сохранения',
        description: 'Network Error',
      }),
    );
  });
});
