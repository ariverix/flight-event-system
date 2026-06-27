/**
 * Unit-тесты useSubscription.
 *
 * Проверяем:
 *  - хук подписывается на wsClient при монтировании
 *  - отписывается (вызывает unsubscribe) при размонтировании
 *  - при enabled=false подписка не создаётся
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useSubscription } from '../useSubscription';
import { wsClient } from '../WsClient';

// ── Мок модуля WsClient ────────────────────────────────────────────────────────
// vi.mock поднимается выше импортов (hoisted), поэтому путь — относительно файла теста
vi.mock('../WsClient', () => ({
  wsClient: {
    subscribe:    vi.fn(),
    connect:      vi.fn(),
    disconnect:   vi.fn(),
  },
}));

// ── Setup ──────────────────────────────────────────────────────────────────────
beforeEach(() => {
  vi.clearAllMocks();
});

// ── Тесты ─────────────────────────────────────────────────────────────────────
describe('useSubscription', () => {
  it('вызывает wsClient.subscribe при монтировании', () => {
    const listener = vi.fn();
    const unsubMock = vi.fn();
    vi.mocked(wsClient.subscribe).mockReturnValue(unsubMock);

    renderHook(() => useSubscription('instance-status', listener));

    expect(wsClient.subscribe).toHaveBeenCalledOnce();
    expect(wsClient.subscribe).toHaveBeenCalledWith('instance-status', listener);
  });

  it('вызывает unsubscribe при размонтировании', () => {
    const listener = vi.fn();
    const unsubMock = vi.fn();
    vi.mocked(wsClient.subscribe).mockReturnValue(unsubMock);

    const { unmount } = renderHook(() => useSubscription('instance-status', listener));

    expect(unsubMock).not.toHaveBeenCalled();
    unmount();
    expect(unsubMock).toHaveBeenCalledOnce();
  });

  it('listener не вызывается после размонтирования (end-to-end)', () => {
    const listener = vi.fn();
    // Реальная функция отписки, которую мы поймали при subscribe
    let capturedUnsub: (() => void) | null = null;

    vi.mocked(wsClient.subscribe).mockImplementation((_channel, _listener) => {
      const unsub = vi.fn();
      capturedUnsub = unsub;
      return unsub;
    });

    const { unmount } = renderHook(() => useSubscription('instance-status', listener));
    unmount();

    // После unmount capturedUnsub должен быть вызван
    expect(capturedUnsub).not.toBeNull();
    expect(capturedUnsub!).toHaveBeenCalledOnce();
  });

  it('НЕ вызывает subscribe при enabled=false', () => {
    const listener = vi.fn();
    renderHook(() => useSubscription('instance-status', listener, false));
    expect(wsClient.subscribe).not.toHaveBeenCalled();
  });

  it('при смене enabled false→true подписывается', () => {
    const listener = vi.fn();
    const unsubMock = vi.fn();
    vi.mocked(wsClient.subscribe).mockReturnValue(unsubMock);

    const { rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) =>
        useSubscription('instance-status', listener, enabled),
      { initialProps: { enabled: false } },
    );

    expect(wsClient.subscribe).not.toHaveBeenCalled();

    rerender({ enabled: true });
    expect(wsClient.subscribe).toHaveBeenCalledOnce();
  });
});
