import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import { AuditLogPage } from '../AuditLogPage';
import { auditApi } from '../../../api/auditApi';
import type { AuditLogEntry } from '../../../api/auditApi';
import type { PageResponse } from '../../../types/sequence';

vi.mock('../../../api/auditApi', () => ({
  auditApi: { getLogs: vi.fn() },
}));

const { notifyError } = vi.hoisted(() => ({ notifyError: vi.fn() }));
vi.mock('../../../hooks/useNotification', () => ({
  useNotification: () => ({ error: notifyError, success: vi.fn() }),
}));

const mockedGetLogs = vi.mocked(auditApi.getLogs);

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

function entryOf(overrides: Partial<AuditLogEntry> = {}): AuditLogEntry {
  return {
    id:            1,
    userId:        7,
    action:        'CREATE_SEQUENCE',
    entityType:    'SEQUENCE',
    entityId:      42,
    detailsJson:   null,
    correlationId: null,
    createdAt:     '2026-01-15T10:00:00',
    ...overrides,
  };
}

function pageOf(content: AuditLogEntry[]): PageResponse<AuditLogEntry> {
  return { content, totalElements: content.length, totalPages: 1, size: 20, number: 0 };
}

beforeEach(() => {
  mockedGetLogs.mockReset();
  notifyError.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('AuditLogPage', () => {
  it('рендерит операцию и сущность из словаря (без хардкода)', async () => {
    mockedGetLogs.mockResolvedValueOnce(pageOf([entryOf()]));
    render(<AuditLogPage />);

    await waitFor(() => expect(screen.getByText('Создана последовательность')).toBeTruthy());

    expect(screen.getAllByText('Последовательность').length).toBeGreaterThan(0);
    expect(screen.getByText('Журнал аудита')).toBeTruthy();
  });

  it('запись без userId: показывает тег «Система»', async () => {
    mockedGetLogs.mockResolvedValueOnce(pageOf([entryOf({ userId: null })]));
    render(<AuditLogPage />);

    await waitFor(() => expect(screen.getByText('Система')).toBeTruthy());
  });

  it('ошибка загрузки: показывает нотификацию из словаря', async () => {
    mockedGetLogs.mockRejectedValueOnce({ response: undefined, message: 'Network Error' });
    render(<AuditLogPage />);

    await waitFor(() =>
      expect(notifyError).toHaveBeenCalledWith({
        message:     'Ошибка загрузки журнала аудита',
        description: 'Network Error',
      }),
    );
  });

  it('без записей: показывает пустое состояние из словаря', async () => {
    mockedGetLogs.mockResolvedValueOnce(pageOf([]));
    render(<AuditLogPage />);

    await waitFor(() => expect(screen.getByText('Записей аудита нет')).toBeTruthy());
    expect(screen.getByText('Действия в системе будут отражены здесь')).toBeTruthy();
  });
});
