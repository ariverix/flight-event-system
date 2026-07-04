import { describe, it, expect, vi, beforeEach } from 'vitest';
import { aircraftApi } from '../aircraftApi';
import api from '../axiosConfig';

vi.mock('../axiosConfig', () => ({
  default: { get: vi.fn() },
}));

const mockedGet = vi.mocked(api.get);

describe('aircraftApi.list', () => {
  beforeEach(() => {
    mockedGet.mockReset();
    mockedGet.mockResolvedValue({
      data: { content: [{ aircraftId: 'VP-BQR', lastSeen: '2026-07-04T10:00:00', messageCount: 3, flightCount: 2 }], totalElements: 1 },
    });
  });

  it('вызывает GET /aircraft с дефолтной пагинацией и без search', async () => {
    await aircraftApi.list();
    expect(mockedGet).toHaveBeenCalledWith('/aircraft', { params: { page: 0, size: 20 } });
  });

  it('пробрасывает page/size и обрезанный search', async () => {
    await aircraftApi.list({ search: '  vp-  ', page: 2, size: 50 });
    expect(mockedGet).toHaveBeenCalledWith('/aircraft', { params: { page: 2, size: 50, search: 'vp-' } });
  });

  it('пустой/пробельный search не отправляется как параметр', async () => {
    await aircraftApi.list({ search: '   ' });
    expect(mockedGet).toHaveBeenCalledWith('/aircraft', { params: { page: 0, size: 20 } });
  });

  it('возвращает страницу бортов из ответа', async () => {
    const page = await aircraftApi.list();
    expect(page.content?.[0]?.aircraftId).toBe('VP-BQR');
    expect(page.totalElements).toBe(1);
  });
});
