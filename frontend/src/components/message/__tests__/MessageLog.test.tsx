import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import { MessageLog } from '../MessageLog';
import { messageApi } from '../../../api/messageApi';
import { aircraftApi } from '../../../api/aircraftApi';
import type { MessageResponse } from '../../../types/message';
import type { PageResponse } from '../../../types/sequence';

vi.mock('../../../api/messageApi', () => ({
  messageApi: { getMessages: vi.fn(), sendMessage: vi.fn(), changeFlightStage: vi.fn() },
}));

vi.mock('../../../api/aircraftApi', () => ({
  aircraftApi: { list: vi.fn() },
}));

// vi.mock хойстится выше объявлений — спай для фабрики только через vi.hoisted
const { notifyError } = vi.hoisted(() => ({ notifyError: vi.fn() }));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ error: notifyError }),
}));

const mockedGetMessages = vi.mocked(messageApi.getMessages);
const mockedAircraftList = vi.mocked(aircraftApi.list);

// jsdom не реализует matchMedia, а antd Table (responsive-пагинация) его требует
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

function messageOf(id: number, overrides: Partial<MessageResponse> = {}): MessageResponse {
  return {
    id,
    messageType: 'DOWNLINK',
    templateName: 'OOOI Report',
    aircraftId: 'VP-BQR',
    flightNumber: 'SU1234',
    receivedAt: '2026-07-04T10:00:00',
    metadataJson: null,
    ...overrides,
  };
}

function pageOf(content: MessageResponse[], totalElements = content.length): PageResponse<MessageResponse> {
  return { content, totalElements, totalPages: Math.ceil(totalElements / 20), size: 20, number: 0 };
}

function typeSelectCombo(): HTMLElement {
  return screen.getByRole('combobox', { name: 'Тип сообщения' });
}

/** Клик по опции открытого дропдауна AntD (визуальный узел .ant-select-item-option-content). */
function clickDropdownOption(text: RegExp): void {
  const contents = Array.from(document.querySelectorAll('.ant-select-item-option-content'));
  const target = contents.find((el) => text.test(el.textContent ?? ''));
  expect(target, `опция дропдауна ${text} не найдена`).toBeTruthy();
  fireEvent.click(target!);
}

describe('MessageLog', () => {
  beforeEach(() => {
    mockedGetMessages.mockReset();
    mockedGetMessages.mockResolvedValue(pageOf([messageOf(1)]));
    mockedAircraftList.mockReset();
    mockedAircraftList.mockResolvedValue({
      content: [{ aircraftId: 'VP-BQR', lastSeen: '2026-07-04T10:00:00', messageCount: 3, flightCount: 2 }],
      totalElements: 1,
    });
    notifyError.mockReset();
  });

  // vite.config: globals=false → авто-cleanup @testing-library не активен, чистим вручную
  afterEach(() => {
    cleanup();
  });

  it('mount: загружает первую страницу без фильтров и рендерит строки', async () => {
    render(<MessageLog />);

    await waitFor(() =>
      expect(mockedGetMessages).toHaveBeenCalledWith(0, 20, undefined, undefined, undefined, undefined),
    );
    // строка таблицы: шаблон, борт, русская метка типа
    expect(await screen.findByText('OOOI Report')).toBeTruthy();
    expect(screen.getByText('✈ VP-BQR')).toBeTruthy();
    expect(screen.getByText('Нисходящая')).toBeTruthy();
    expect(screen.getByText('SU1234')).toBeTruthy();
  });

  it('интеграция AircraftPicker: выбор борта перезапрашивает журнал с aircraftId и сбросом на 1-ю страницу', async () => {
    render(<MessageLog />);
    await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledTimes(1));

    const aircraftCombo = screen.getByRole('combobox', { name: 'Борт (tail number)' });
    fireEvent.mouseDown(aircraftCombo);
    // ждём загрузку опций пикера (mount-фетч aircraftApi.list)
    await waitFor(() =>
      expect(document.querySelectorAll('.ant-select-item-option-content').length).toBeGreaterThan(0),
    );
    clickDropdownOption(/^VP-BQR/);

    await waitFor(() =>
      expect(mockedGetMessages).toHaveBeenLastCalledWith(0, 20, 'VP-BQR', undefined, undefined, undefined),
    );
  });

  it('фильтр по типу: выбор «Восходящая» перезапрашивает журнал с messageType=UPLINK', async () => {
    render(<MessageLog />);
    await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledTimes(1));

    fireEvent.mouseDown(typeSelectCombo());
    await waitFor(() =>
      expect(document.querySelectorAll('.ant-select-item-option-content').length).toBeGreaterThan(0),
    );
    clickDropdownOption(/^Восходящая$/);

    await waitFor(() =>
      expect(mockedGetMessages).toHaveBeenLastCalledWith(0, 20, undefined, 'UPLINK', undefined, undefined),
    );
  });

  it('пагинация: переход на страницу 2 запрашивает page=1 с текущим размером', async () => {
    mockedGetMessages.mockResolvedValue(pageOf([messageOf(1)], 50));
    render(<MessageLog />);
    await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('1–20 из 50')).toBeTruthy();

    fireEvent.click(screen.getByTitle('2'));

    await waitFor(() =>
      expect(mockedGetMessages).toHaveBeenLastCalledWith(1, 20, undefined, undefined, undefined, undefined),
    );
  });

  it('кнопка «Обновить» перезапрашивает журнал с текущими фильтрами', async () => {
    render(<MessageLog />);
    await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByText('Обновить'));

    await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledTimes(2));
    expect(mockedGetMessages).toHaveBeenLastCalledWith(0, 20, undefined, undefined, undefined, undefined);
  });

  it('ошибка загрузки → уведомление об ошибке, скелетон уходит', async () => {
    mockedGetMessages.mockReset();
    mockedGetMessages.mockRejectedValue(new Error('HTTP 500'));

    render(<MessageLog />);

    await waitFor(() => expect(notifyError).toHaveBeenCalledTimes(1));
    expect(notifyError.mock.calls[0][0].message).toBe('Ошибка загрузки журнала');
    // finally-ветка: loading снят, вместо скелетона — таблица с empty-состоянием
    expect(await screen.findByText('Сообщений нет')).toBeTruthy();
  });

  it('пустой журнал → empty-состояние с подсказкой про Симулятор', async () => {
    mockedGetMessages.mockResolvedValue(pageOf([]));
    render(<MessageLog />);

    expect(await screen.findByText('Сообщений нет')).toBeTruthy();
    expect(screen.getByText('Отправьте событие через Симулятор чтобы увидеть сообщения')).toBeTruthy();
  });

  it('метаданные: валидный JSON сворачивается в превью первых ключей', async () => {
    mockedGetMessages.mockResolvedValue(
      pageOf([messageOf(1, { metadataJson: '{"out":"10:00","off":"10:15","on":"12:40"}' })]),
    );
    render(<MessageLog />);

    expect(await screen.findByText('{out, off…}')).toBeTruthy();
  });
});
