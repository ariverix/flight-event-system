import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { TimelinePage } from '../TimelinePage';
import { useTimeline } from '../../hooks/useTimeline';
import type { TLEvent } from '../../hooks/useTimeline';

vi.mock('../../hooks/useTimeline', () => ({
  useTimeline: vi.fn(),
}));

const mockedUseTimeline = vi.mocked(useTimeline);

function timelineOf(overrides: Partial<ReturnType<typeof useTimeline>> = {}): ReturnType<typeof useTimeline> {
  return {
    all: [], visible: [], loading: false, error: null,
    playing: false, idx: 0, speed: 1,
    progress: 0, total: 0, shown: 0,
    play: vi.fn(), pause: vi.fn(), reset: vi.fn(), toEnd: vi.fn(),
    setSpeed: vi.fn(), reload: vi.fn(),
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('TimelinePage', () => {
  it('рендерит заголовок и подзаголовок из словаря (без хардкода)', () => {
    mockedUseTimeline.mockReturnValue(timelineOf());
    render(<TimelinePage />);

    expect(screen.getByText('Хронология полёта')).toBeTruthy();
    expect(screen.getByText('Живая лента событий с воспроизведением истории')).toBeTruthy();
  });

  it('нет событий: показывает пустое состояние с бортом из словаря', () => {
    mockedUseTimeline.mockReturnValue(timelineOf({ total: 0 }));
    render(<TimelinePage />);

    expect(screen.getByText(/Нет событий для борта/)).toBeTruthy();
    expect(screen.getByText('Попробуйте другой борт или отправьте событие через Симулятор')).toBeTruthy();
  });

  it('есть история, но воспроизведение не начато: показывает подсказку play', () => {
    const ev: TLEvent = { id: '1', type: 'MESSAGE_RECEIVED', timestamp: '2026-01-15T10:00:00Z', aircraftId: 'SU9876' };
    mockedUseTimeline.mockReturnValue(timelineOf({ all: [ev], total: 1, shown: 0 }));
    render(<TimelinePage />);

    expect(screen.getByText('Нажмите ▶ для воспроизведения')).toBeTruthy();
    expect(screen.getByText('1 событий в истории')).toBeTruthy();
  });

  it('во время воспроизведения показывает кнопку «Пауза»', () => {
    mockedUseTimeline.mockReturnValue(timelineOf({ playing: true, total: 1, shown: 1 }));
    render(<TimelinePage />);

    expect(screen.getByText('Пауза')).toBeTruthy();
  });

  it('статистика рендерит переведённые лейблы', () => {
    mockedUseTimeline.mockReturnValue(timelineOf());
    render(<TimelinePage />);

    expect(screen.getByText('Статистика')).toBeTruthy();
    expect(screen.getByText('Сообщений')).toBeTruthy();
    expect(screen.getByText('Запусков')).toBeTruthy();
    expect(screen.getByText('Шагов')).toBeTruthy();
    expect(screen.getByText('Ошибок')).toBeTruthy();
  });
});
