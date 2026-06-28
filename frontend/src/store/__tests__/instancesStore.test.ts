/**
 * Unit-тесты useInstancesStore (P7-4).
 *
 * Проверяем:
 *  (a) connect() вызывает wsClient.connect() и подписывается на нужные каналы
 *  (b) instance-status payload обновляет map instances
 *  (c) event-log payload prepend'ится в массив eventLog
 *  (d) cap 500: eventLog не превышает MAX_EVENT_LOG записей
 *  (e) disconnect() вызывает wsClient.disconnect() и сбрасывает connected
 *  (f) reset() очищает состояние
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useInstancesStore } from '../instancesStore';
import type { InstanceStatusPayload, EventLogPayload } from '../../api/ws/types';

// ── Мок WsClient ─────────────────────────────────────────────────────────────
vi.mock('../../api/ws/WsClient', () => {
  const subscribeListeners: Map<string, ((payload: unknown) => void)[]> = new Map();

  const subscribe = vi.fn((channel: string, listener: (p: unknown) => void) => {
    const list = subscribeListeners.get(channel) ?? [];
    list.push(listener);
    subscribeListeners.set(channel, list);
    return () => {
      const l = subscribeListeners.get(channel);
      if (l) {
        const idx = l.indexOf(listener);
        if (idx >= 0) l.splice(idx, 1);
      }
    };
  });

  // Expose для тестов
  (subscribe as unknown as { _emit: (ch: string, p: unknown) => void })._emit =
    (channel: string, payload: unknown) => {
      subscribeListeners.get(channel)?.forEach((fn) => fn(payload));
    };

  return {
    wsClient: {
      connect:    vi.fn(),
      disconnect: vi.fn(),
      subscribe,
    },
  };
});

// ── Хелперы ──────────────────────────────────────────────────────────────────
import { wsClient } from '../../api/ws/WsClient';

type SubscribeMock = typeof wsClient.subscribe & {
  _emit: (channel: string, payload: unknown) => void;
};

function emit(channel: string, payload: unknown) {
  (wsClient.subscribe as SubscribeMock)._emit(channel, payload);
}

function makeStatus(instanceId: number): InstanceStatusPayload {
  return {
    instanceId,
    sequenceId:       10,
    aircraftId:       'VP-BQR',
    flightNumber:     'SU1234',
    status:           'RUNNING',
    currentStepIndex: 1,
    updatedAt:        '2026-01-01T00:00:00Z',
  };
}

function makeEventLog(id: number, instanceId: number): EventLogPayload {
  return {
    id,
    eventType:     'STEP_COMPLETED',
    instanceId,
    sequenceId:    10,
    aircraftId:    'VP-BQR',
    flightNumber:  'SU1234',
    stepIndex:     1,
    detailsJson:   null,
    correlationId: null,
    createdAt:     '2026-01-01T00:00:00Z',
  };
}

// ── Setup ─────────────────────────────────────────────────────────────────────
beforeEach(() => {
  // Сброс состояния и моков между тестами
  useInstancesStore.getState().reset();
  vi.clearAllMocks();
});

// ── Тесты ─────────────────────────────────────────────────────────────────────
describe('useInstancesStore', () => {

  // ── (a) connect() ─────────────────────────────────────────────────────────
  describe('connect()', () => {
    it('вызывает wsClient.connect()', () => {
      useInstancesStore.getState().connect();
      expect(wsClient.connect).toHaveBeenCalledOnce();
    });

    it('устанавливает connected = true', () => {
      useInstancesStore.getState().connect();
      expect(useInstancesStore.getState().connected).toBe(true);
    });

    it('подписывается на instance-status и event-log', () => {
      useInstancesStore.getState().connect();
      const calls = vi.mocked(wsClient.subscribe).mock.calls.map((c) => c[0]);
      expect(calls).toContain('instance-status');
      expect(calls).toContain('event-log');
    });

    it('повторный вызов connect() не создаёт лишних подписок', () => {
      useInstancesStore.getState().connect();
      useInstancesStore.getState().connect();
      // Должен вызвать subscribe ровно 2 раза (по каналу), не 4
      expect(vi.mocked(wsClient.subscribe).mock.calls.length).toBe(2);
    });
  });

  // ── (b) instance-status обновляет map ────────────────────────────────────
  describe('instance-status payload', () => {
    it('добавляет новый инстанс в map по ключу instanceId', () => {
      useInstancesStore.getState().connect();

      const payload = makeStatus(42);
      emit('instance-status', payload);

      const instances = useInstancesStore.getState().instances;
      expect(instances['42']).toEqual(payload);
    });

    it('обновляет существующий инстанс при повторном событии', () => {
      useInstancesStore.getState().connect();

      emit('instance-status', makeStatus(1));
      const updated: InstanceStatusPayload = {
        ...makeStatus(1),
        status:           'WAITING',
        currentStepIndex: 3,
      };
      emit('instance-status', updated);

      expect(useInstancesStore.getState().instances['1']?.status).toBe('WAITING');
      expect(useInstancesStore.getState().instances['1']?.currentStepIndex).toBe(3);
    });

    it('не затирает другие инстансы при обновлении одного', () => {
      useInstancesStore.getState().connect();

      emit('instance-status', makeStatus(1));
      emit('instance-status', makeStatus(2));

      const { instances } = useInstancesStore.getState();
      expect(instances['1']).toBeDefined();
      expect(instances['2']).toBeDefined();
    });
  });

  // ── (c) event-log prepend ─────────────────────────────────────────────────
  describe('event-log payload', () => {
    it('prepend'ит новое событие в начало массива', () => {
      useInstancesStore.getState().connect();

      emit('event-log', makeEventLog(1, 1));
      emit('event-log', makeEventLog(2, 1));

      const { eventLog } = useInstancesStore.getState();
      expect(eventLog[0].id).toBe(2); // последнее событие — первое в массиве
      expect(eventLog[1].id).toBe(1);
    });
  });

  // ── (d) cap 500 ───────────────────────────────────────────────────────────
  describe('eventLog cap', () => {
    it('не превышает 500 записей', () => {
      useInstancesStore.getState().connect();

      for (let i = 0; i < 510; i++) {
        emit('event-log', makeEventLog(i, 1));
      }

      expect(useInstancesStore.getState().eventLog.length).toBe(500);
    });

    it('хранит самые свежие 500 событий (первые в массиве = самые новые)', () => {
      useInstancesStore.getState().connect();

      for (let i = 0; i < 510; i++) {
        emit('event-log', makeEventLog(i, 1));
      }

      // id=509 — последнее отправленное (самое свежее) — должно быть первым
      expect(useInstancesStore.getState().eventLog[0].id).toBe(509);
    });
  });

  // ── (e) disconnect() ──────────────────────────────────────────────────────
  describe('disconnect()', () => {
    it('вызывает wsClient.disconnect()', () => {
      useInstancesStore.getState().connect();
      useInstancesStore.getState().disconnect();
      expect(wsClient.disconnect).toHaveBeenCalledOnce();
    });

    it('сбрасывает connected = false', () => {
      useInstancesStore.getState().connect();
      useInstancesStore.getState().disconnect();
      expect(useInstancesStore.getState().connected).toBe(false);
    });

    it('после disconnect() WS-события больше не обновляют state', () => {
      useInstancesStore.getState().connect();
      useInstancesStore.getState().disconnect();

      emit('instance-status', makeStatus(99));

      expect(useInstancesStore.getState().instances['99']).toBeUndefined();
    });
  });

  // ── (f) reset() ───────────────────────────────────────────────────────────
  describe('reset()', () => {
    it('очищает instances и eventLog', () => {
      useInstancesStore.getState().connect();
      emit('instance-status', makeStatus(1));
      emit('event-log', makeEventLog(1, 1));

      useInstancesStore.getState().reset();

      const { instances, eventLog } = useInstancesStore.getState();
      expect(Object.keys(instances).length).toBe(0);
      expect(eventLog.length).toBe(0);
    });

    it('сбрасывает connected = false', () => {
      useInstancesStore.getState().connect();
      useInstancesStore.getState().reset();
      expect(useInstancesStore.getState().connected).toBe(false);
    });
  });
});
