import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SequenceList } from '../SequenceList';
import { sequenceApi } from '../../../api/sequenceApi';
import type { SequenceResponse } from '../../../types/sequence';
import type { PageResponse } from '../../../types/sequence';

vi.mock('../../../api/sequenceApi', () => ({
  sequenceApi: {
    getSequences:      vi.fn(),
    deleteSequence:    vi.fn(),
    activateSequence:  vi.fn(),
    deactivateSequence: vi.fn(),
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
}));
vi.mock('../../../hooks/useAuth', () => ({
  useAuth: () => ({ isAdmin: true }),
}));

const mockedGetSequences = vi.mocked(sequenceApi.getSequences);
const mockedActivate     = vi.mocked(sequenceApi.activateSequence);

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

function seqOf(overrides: Partial<SequenceResponse> = {}): SequenceResponse {
  return {
    id:                1,
    name:              'Проверка после взлёта',
    description:       'Тестовая последовательность',
    status:            'INACTIVE',
    startCriteriaJson: null,
    stopCriteriaJson:  null,
    createdAt:         '2026-01-15T10:00:00',
    updatedAt:         '2026-01-15T10:00:00',
    steps:             [],
    ...overrides,
  };
}

function pageOf(content: SequenceResponse[]): PageResponse<SequenceResponse> {
  return { content, totalElements: content.length, totalPages: 1, size: 10, number: 0 };
}

beforeEach(() => {
  mockedGetSequences.mockReset();
  mockedActivate.mockReset();
  notifySuccess.mockReset();
  notifyError.mockReset();
  navigate.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('SequenceList', () => {
  it('рендерит статус и заголовок из словаря (без хардкода)', async () => {
    mockedGetSequences.mockResolvedValue(pageOf([seqOf()]));
    render(<SequenceList />);

    await waitFor(() => expect(screen.getByText('Проверка после взлёта')).toBeTruthy());

    expect(screen.getByText('Последовательности событий')).toBeTruthy();
    expect(screen.getByText('Неактивна')).toBeTruthy();
  });

  it('активация неактивной последовательности с шагами: зовёт activateSequence()', async () => {
    mockedGetSequences.mockResolvedValue(
      pageOf([seqOf({ id: 5, status: 'INACTIVE', steps: [{ id: 1 } as never] })]),
    );
    mockedActivate.mockResolvedValueOnce(seqOf({ id: 5, status: 'ACTIVE' }));
    const user = userEvent.setup();
    render(<SequenceList />);

    await waitFor(() => expect(screen.getByText('Активировать')).toBeTruthy());
    await user.click(screen.getByText('Активировать'));

    await waitFor(() => expect(mockedActivate).toHaveBeenCalledWith(5));
    expect(notifySuccess).toHaveBeenCalledWith({ message: 'Последовательность активирована' });
  });

  it('ошибка загрузки: показывает нотификацию из словаря', async () => {
    mockedGetSequences.mockRejectedValue({ response: undefined, message: 'Network Error' });
    render(<SequenceList />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки последовательностей',
        description: 'Network Error',
      }),
    );
  });

  it('без последовательностей: показывает пустое состояние из словаря', async () => {
    mockedGetSequences.mockResolvedValue(pageOf([]));
    render(<SequenceList />);

    await waitFor(() => expect(screen.getByText('Последовательностей нет')).toBeTruthy());
    expect(screen.getByText('Создайте первую последовательность событий')).toBeTruthy();
  });
});
