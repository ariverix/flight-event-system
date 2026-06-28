/**
 * Zustand-стор состояния реал-тайм инстансов (P7-4).
 *
 * Источник данных — WebSocket-канал:
 *   instance-status → обновляет Map инстансов (key = instanceId.toString())
 *   event-log       → prepend к очереди eventLog (cap MAX_EVENT_LOG)
 *
 * connect() вызывается при монтировании InstancesDashboard; disconnect() — при размонтировании.
 * Токен для WS-аутентификации берётся из localStorage('jwt') самим WsClient (ADR-0005 п.4).
 */
import { create } from 'zustand';
import { wsClient } from '../api/ws/WsClient';
import type { InstanceStatusPayload, EventLogPayload } from '../api/ws/types';

// ── Переэкспорт типов для потребителей ───────────────────────────────────────
export type InstanceStatus = InstanceStatusPayload;
export type EventLogEntry  = EventLogPayload;

// ── Константы ─────────────────────────────────────────────────────────────────
const MAX_EVENT_LOG = 500;

// ── Handles отписки (module-level — store является синглтоном) ─────────────────
let _unsubStatus:   (() => void) | null = null;
let _unsubEventLog: (() => void) | null = null;

// ── Интерфейс стора ───────────────────────────────────────────────────────────
export interface InstancesState {
  /** Карта всех инстансов, обновляемых по WS; key = instanceId.toString() */
  instances: Record<string, InstanceStatus>;

  /** Лента событий (prepend, cap 500), фильтруется в компоненте по instanceId */
  eventLog: EventLogEntry[];

  /** Отражает вызов connect() / disconnect() (оптимистичный флаг) */
  connected: boolean;

  /**
   * Открыть WS-соединение и подписаться на каналы.
   * Токен для аутентификации первым сообщением берётся WsClient из localStorage('jwt').
   * @param _token — принимается для совместимости интерфейса; WsClient управляет токеном сам.
   */
  connect: (_token?: string) => void;

  /** Закрыть WS-соединение и освободить подписки. */
  disconnect: () => void;

  /** Сбросить накопленные данные (при logout / переключении контекста). */
  reset: () => void;
}

// ── Создание стора ────────────────────────────────────────────────────────────
export const useInstancesStore = create<InstancesState>()((set) => ({
  instances: {},
  eventLog:  [],
  connected: false,

  connect: (_token?: string) => {
    // Защита от двойного вызова
    if (_unsubStatus !== null) return;

    wsClient.connect();
    set({ connected: true });

    _unsubStatus = wsClient.subscribe('instance-status', (payload: InstanceStatus) => {
      set((state) => ({
        instances: {
          ...state.instances,
          [String(payload.instanceId)]: payload,
        },
      }));
    });

    _unsubEventLog = wsClient.subscribe('event-log', (payload: EventLogEntry) => {
      set((state) => ({
        eventLog: [payload, ...state.eventLog].slice(0, MAX_EVENT_LOG),
      }));
    });
  },

  disconnect: () => {
    _unsubStatus?.();
    _unsubEventLog?.();
    _unsubStatus   = null;
    _unsubEventLog = null;
    wsClient.disconnect();
    set({ connected: false });
  },

  reset: () => {
    _unsubStatus?.();
    _unsubEventLog?.();
    _unsubStatus   = null;
    _unsubEventLog = null;
    set({ instances: {}, eventLog: [], connected: false });
  },
}));
