/**
 * Типобезопасные конверты WebSocket-сообщений ECA.
 *
 * Формат: { channel: string; payload: <T> }
 * Каналы соответствуют будущим STOMP-топикам бэкенда (P7-4):
 *   /topic/instance-status  — статус инстанса выполнения
 *   /topic/event-log        — запись Event Log класса Tracking
 *
 * Бэкенд-эндпоинт WS будет реализован в P7-4. Этот слой готов к
 * подключению без изменений — нужно только задать VITE_WS_URL.
 */

// ── Статус инстанса ────────────────────────────────────────────────────────────
export interface InstanceStatusPayload {
  instanceId:       number;
  sequenceId:       number;
  aircraftId:       string;
  flightNumber:     string | null;
  status:           'RUNNING' | 'WAITING' | 'COMPLETED' | 'ABORTED';
  currentStepIndex: number | null;
  updatedAt:        string;
}

// ── Запись Event Log ───────────────────────────────────────────────────────────
export interface EventLogPayload {
  id:            number;
  eventType:     'SEQUENCE_STARTED' | 'STEP_COMPLETED' | 'SEQUENCE_STOPPED' | 'SEQUENCE_ABORTED';
  instanceId:    number;
  sequenceId:    number | null;
  aircraftId:    string;
  flightNumber:  string | null;
  stepIndex:     number | null;
  detailsJson:   string | null;
  /** Сквозной идентификатор запроса/сообщения (может отсутствовать). */
  correlationId: string | null;
  createdAt:     string;
}

// ── Ping / Pong ────────────────────────────────────────────────────────────────
export interface PingPayload { ts: number }
export interface PongPayload { ts: number }

// ── Аутентификация (клиент → сервер, первое сообщение, ADR-0005) ───────────────
export interface AuthPayload { token: string }

// ── Дискриминированный union всех каналов ─────────────────────────────────────
export type WsMessage =
  | { channel: 'auth';            payload: AuthPayload }
  | { channel: 'instance-status'; payload: InstanceStatusPayload }
  | { channel: 'event-log';       payload: EventLogPayload }
  | { channel: 'ping';            payload: PingPayload }
  | { channel: 'pong';            payload: PongPayload };

export type WsChannel = WsMessage['channel'];

/** Тип payload для конкретного канала */
export type WsPayload<C extends WsChannel> = Extract<WsMessage, { channel: C }>['payload'];
