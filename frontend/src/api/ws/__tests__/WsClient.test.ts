/**
 * Unit-тесты WsClient.
 *
 * Проверяем новое поведение, добавленное в P7-1:
 *  (a) нет-оп при отсутствии URL — WebSocket не создаётся, ошибки нет
 *  (b) subscribe/unsubscribe мультиплексинг
 *  (c) ping → pong
 *  (d) первый reconnect через 1000 мс (exponential backoff, retryCount=0)
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WsClient } from '../WsClient';
import type { InstanceStatusPayload } from '../types';

// ── WebSocket mock ─────────────────────────────────────────────────────────────
interface MockSocket {
  readyState: number;
  close:      ReturnType<typeof vi.fn>;
  send:       ReturnType<typeof vi.fn>;
  onopen:     (() => void) | null;
  onmessage:  ((ev: { data: string }) => void) | null;
  onclose:    (() => void) | null;
  onerror:    (() => void) | null;
}

// currentMock is reassigned each time the constructor runs
let currentMock: MockSocket;

function makeMock(): MockSocket {
  return {
    readyState: 0, // CONNECTING
    close:      vi.fn(),
    send:       vi.fn(),
    onopen:     null,
    onmessage:  null,
    onclose:    null,
    onerror:    null,
  };
}

// Constructor function — vi.fn infers its type from the implementation
const mockConstructor = vi.fn(function wsFactory() {
  currentMock = makeMock();
  return currentMock;
});

// Тестовый payload для канала instance-status
const PAYLOAD: InstanceStatusPayload = {
  instanceId:       1,
  sequenceId:       2,
  aircraftId:       'VP-BQR',
  flightNumber:     'SU1234',
  status:           'RUNNING',
  currentStepIndex: 0,
  updatedAt:        '2026-01-01T00:00:00Z',
};

// ── Общий setup ────────────────────────────────────────────────────────────────
beforeEach(() => {
  mockConstructor.mockClear();
  // Переинициализируем currentMock; будет перезаписан при первом вызове конструктора
  currentMock = makeMock();
  // Добавляем статические константы WebSocket API
  const stub = Object.assign(mockConstructor, {
    CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3,
  });
  vi.stubGlobal('WebSocket', stub);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// ── (a) Нет-оп без URL ────────────────────────────────────────────────────────
describe('connect() — no-op when URL is null', () => {
  it('не создаёт WebSocket и не бросает ошибку', () => {
    const client = new WsClient(null);
    expect(() => client.connect()).not.toThrow();
    expect(mockConstructor).not.toHaveBeenCalled();
  });

  it('повторный вызов connect() также является нет-опом', () => {
    const client = new WsClient(null);
    client.connect();
    client.connect();
    expect(mockConstructor).not.toHaveBeenCalled();
  });
});

// ── (b) subscribe / unsubscribe мультиплексинг ────────────────────────────────
describe('subscribe / unsubscribe', () => {
  it('listener получает payload правильного канала', () => {
    const client = new WsClient('ws://localhost:8080');
    const listener = vi.fn();
    client.subscribe('instance-status', listener);
    client.connect();

    currentMock.onmessage!({ data: JSON.stringify({ channel: 'instance-status', payload: PAYLOAD }) });

    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith(PAYLOAD);
    client.disconnect();
  });

  it('listener НЕ получает payload другого канала', () => {
    const client = new WsClient('ws://localhost:8080');
    const listener = vi.fn();
    client.subscribe('event-log', listener);
    client.connect();

    currentMock.onmessage!({ data: JSON.stringify({ channel: 'instance-status', payload: PAYLOAD }) });

    expect(listener).not.toHaveBeenCalled();
    client.disconnect();
  });

  it('после unsubscribe listener больше не вызывается', () => {
    const client = new WsClient('ws://localhost:8080');
    const listener = vi.fn();
    const unsub = client.subscribe('instance-status', listener);
    client.connect();

    // первое сообщение — получаем
    currentMock.onmessage!({ data: JSON.stringify({ channel: 'instance-status', payload: PAYLOAD }) });
    expect(listener).toHaveBeenCalledOnce();

    unsub();

    // второе сообщение — уже не получаем
    currentMock.onmessage!({ data: JSON.stringify({ channel: 'instance-status', payload: PAYLOAD }) });
    expect(listener).toHaveBeenCalledOnce(); // по-прежнему 1 вызов

    client.disconnect();
  });

  it('два listener на одном канале оба получают payload', () => {
    const client = new WsClient('ws://localhost:8080');
    const l1 = vi.fn();
    const l2 = vi.fn();
    client.subscribe('instance-status', l1);
    client.subscribe('instance-status', l2);
    client.connect();

    currentMock.onmessage!({ data: JSON.stringify({ channel: 'instance-status', payload: PAYLOAD }) });

    expect(l1).toHaveBeenCalledWith(PAYLOAD);
    expect(l2).toHaveBeenCalledWith(PAYLOAD);
    client.disconnect();
  });

  it('невалидный JSON в сообщении не бросает ошибку', () => {
    const client = new WsClient('ws://localhost:8080');
    const listener = vi.fn();
    client.subscribe('instance-status', listener);
    client.connect();

    expect(() => currentMock.onmessage!({ data: 'not-json' })).not.toThrow();
    expect(listener).not.toHaveBeenCalled();
    client.disconnect();
  });
});

// ── (c) Ping / Pong ───────────────────────────────────────────────────────────
describe('ping → pong', () => {
  it('получив ping — отправляет pong с полем ts', () => {
    const client = new WsClient('ws://localhost:8080');
    client.connect();
    currentMock.readyState = 1; // OPEN

    currentMock.onmessage!({ data: JSON.stringify({ channel: 'ping', payload: { ts: 99999 } }) });

    expect(currentMock.send).toHaveBeenCalledOnce();
    const sent = JSON.parse(currentMock.send.mock.calls[0][0] as string) as {
      channel: string;
      payload: { ts: number };
    };
    expect(sent.channel).toBe('pong');
    expect(typeof sent.payload.ts).toBe('number');

    client.disconnect();
  });
});

// ── (d) Exponential backoff ───────────────────────────────────────────────────
describe('reconnect backoff', () => {
  it('первый reconnect срабатывает ровно через 1000 мс', () => {
    vi.useFakeTimers();
    const client = new WsClient('ws://localhost:8080');
    client.connect();

    // Имитируем закрытие (в реальном WS readyState становится CLOSED=3)
    currentMock.readyState = 3;
    mockConstructor.mockClear();
    currentMock.onclose!();

    // До 1000 мс — нового WebSocket нет
    vi.advanceTimersByTime(999);
    expect(mockConstructor).not.toHaveBeenCalled();

    // Ровно в 1000 мс — новый WebSocket создаётся
    vi.advanceTimersByTime(1);
    expect(mockConstructor).toHaveBeenCalledOnce();

    client.disconnect();
  });
});
