import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent, act } from '@testing-library/react';
import { AircraftPicker } from '../AircraftPicker';
import { aircraftApi } from '../../../api/aircraftApi';
import type { ApiPageAircraftSummaryResponse } from '../../../api/generated/schema';

vi.mock('../../../api/aircraftApi', () => ({
  aircraftApi: { list: vi.fn() },
}));

const mockedList = vi.mocked(aircraftApi.list);

function pageWith(tail: string): ApiPageAircraftSummaryResponse {
  return {
    content: [{ aircraftId: tail, lastSeen: '2026-07-04T10:00:00', messageCount: 3, flightCount: 2 }],
    totalElements: 1,
  };
}

/** Отложенный промис — ручное управление порядком резолва (тест race-guard). */
function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('AircraftPicker', () => {
  beforeEach(() => {
    mockedList.mockReset();
    mockedList.mockResolvedValue(pageWith('VP-BQR'));
  });

  // vite.config: globals=false → авто-cleanup @testing-library не активен, чистим вручную
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('загружает список бортов при монтировании', async () => {
    render(<AircraftPicker value={null} onChange={() => {}} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalledWith({ search: '', page: 0, size: 20 }));
  });

  it('рендерит combobox с aria-label (a11y)', async () => {
    render(<AircraftPicker value={null} onChange={() => {}} />);
    // getByRole бросает, если элемента нет — само нахождение и есть проверка существования
    const combo = screen.getByRole('combobox');
    // aria-label из i18n (RU-дефолт) — без jest-dom матчеров (в проекте не подключены)
    expect(combo.getAttribute('aria-label')).toBe('Борт (tail number)');
    await waitFor(() => expect(mockedList).toHaveBeenCalled());
  });

  it('debounce: поисковый запрос не уходит раньше 300мс; серия вводов даёт ОДИН запрос', () => {
    vi.useFakeTimers();
    render(<AircraftPicker value={null} onChange={() => {}} />);
    expect(mockedList).toHaveBeenCalledTimes(1); // только mount-фетч (он не debounced)

    const combo = screen.getByRole('combobox');
    fireEvent.change(combo, { target: { value: 'v' } });
    fireEvent.change(combo, { target: { value: 'vp' } }); // перезаводит таймер

    act(() => {
      vi.advanceTimersByTime(299);
    });
    expect(mockedList).toHaveBeenCalledTimes(1); // debounce ещё держит

    act(() => {
      vi.advanceTimersByTime(2);
    });
    expect(mockedList).toHaveBeenCalledTimes(2); // один запрос с последним текстом
    expect(mockedList).toHaveBeenLastCalledWith({ search: 'vp', page: 0, size: 20 });
  });

  it('unmount до истечения debounce: отложенный запрос НЕ уходит (таймер очищен)', () => {
    vi.useFakeTimers();
    const { unmount } = render(<AircraftPicker value={null} onChange={() => {}} />);
    expect(mockedList).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'vp' } });
    unmount();

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(mockedList).toHaveBeenCalledTimes(1); // только mount-фетч, утечки таймера нет
  });

  it('race-guard: ответ УСТАРЕВШЕГО запроса отбрасывается (применяется только последний)', async () => {
    vi.useFakeTimers();
    const older = deferred<ApiPageAircraftSummaryResponse>();
    const newer = deferred<ApiPageAircraftSummaryResponse>();
    mockedList.mockReset();
    mockedList.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise);

    render(<AircraftPicker value={null} onChange={() => {}} />);
    // mount-фетч (older) ещё висит; запускаем более новый поиск
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'vp' } });
    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(mockedList).toHaveBeenCalledTimes(2);

    // новый резолвится ПЕРВЫМ, устаревший — ПОЗЖЕ (классическая гонка медленного ответа)
    await act(async () => {
      newer.resolve(pageWith('VP-NEW'));
    });
    await act(async () => {
      older.resolve(pageWith('VP-OLD'));
    });

    // открываем дропдаун: должен быть только результат нового запроса
    fireEvent.mouseDown(screen.getByRole('combobox'));
    act(() => {
      vi.runAllTimers(); // анимации/отложенный рендер дропдауна AntD
    });
    expect(screen.queryAllByText(/VP-OLD/)).toHaveLength(0);
    // AntD рендерит текст опции в двух узлах (option + content) — важно, что он есть вообще
    expect(screen.getAllByText(/VP-NEW/).length).toBeGreaterThan(0);
  });

  it('ошибка загрузки → error-текст в notFoundContent, опции пусты', async () => {
    mockedList.mockReset();
    mockedList.mockRejectedValue(new Error('HTTP 500'));

    render(<AircraftPicker value={null} onChange={() => {}} />);
    await waitFor(() => expect(mockedList).toHaveBeenCalled());

    fireEvent.mouseDown(screen.getByRole('combobox'));
    await waitFor(() =>
      expect(screen.getByText('Не удалось загрузить список бортов')).toBeTruthy(),
    );
  });
});
