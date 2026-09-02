/**
 * WsClient — WebSocket-клиент с автоматическим переподключением.
 *
 * Поведение:
 * - Принимает URL как параметр конструктора (`string | null`); синглтон ниже резолвит его
 *   через {@link resolveWsUrl}. `null` — режим «нет-оп»: соединение не открывается, подписки
 *   молча игнорируются (приложение не падает) — используется только в юнит-тестах.
 * - Переподключение: экспоненциальный backoff 1 → 2 → 4 → 8 → 16 → 32 с (потолок).
 * - Ping/pong-цикл каждые 30 с для детекции «тихих» разрывов.
 * - Multiplexed подписки: множество обработчиков на один канал.
 *
 * Синглтон: экспортируется единственный экземпляр `wsClient`.
 * Инициализируется в main.tsx после монтирования приложения.
 */
import type { WsChannel, WsMessage, WsPayload } from './types';
import { resolveWsUrl } from './resolveWsUrl';

// Внутренний тип — стёртый listener без привязки к конкретному каналу.
// Используется только внутри Map; публичный API типобезопасен через generics.
type AnyListener = (payload: unknown) => void;

const BACKOFF_CAP_MS = 32_000;
const PING_INTERVAL_MS = 30_000;

export class WsClient {
  private readonly url: string | null;
  private socket: WebSocket | null = null;
  private readonly listeners = new Map<WsChannel, Set<AnyListener>>();
  private retryCount = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private destroyed = false;

  constructor(url: string | null) {
    this.url = url;
  }

  /** Открыть соединение. Нет-оп, если URL не задан. Отменяет предыдущий disconnect(). */
  connect(): void {
    if (!this.url) return;
    this.destroyed = false;
    if (this.socket && this.socket.readyState <= WebSocket.OPEN) return;

    try {
      // ADR-0005 п. 4: JWT НЕ передаётся в URL (оседает в сервер-логах и браузерной истории).
      // Аутентификация — первым сообщением после открытия соединения (см. onopen ниже).
      this.socket = new WebSocket(this.url);
    } catch {
      this.scheduleReconnect();
      return;
    }

    this.socket.onopen = () => {
      // P7-4: аутентификация первым сообщением; сервер закроет соединение, если токен невалиден.
      const token = localStorage.getItem('jwt');
      if (token) {
        this.send({ channel: 'auth', payload: { token } });
      }
      this.retryCount = 0;
      this.startPing();
    };

    this.socket.onmessage = (ev: MessageEvent<unknown>) => {
      this.handleRawMessage(ev.data);
    };

    this.socket.onclose = () => {
      this.stopPing();
      this.scheduleReconnect();
    };

    this.socket.onerror = () => {
      // onclose будет вызван после onerror браузером
    };
  }

  /** Закрыть соединение и освободить ресурсы. Подавляет авто-reconnect до следующего connect(). */
  disconnect(): void {
    this.destroyed = true;
    this.stopPing();
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    if (this.socket) {
      // Отвязываем обработчики ДО close(): браузер доставляет close-event
      // асинхронно и может сделать это уже после того, как connect() успеет
      // открыть новый сокет — без отвязки старый onclose среагирует на чужое
      // состояние (остановит ping нового сокета, распланирует лишний reconnect).
      this.socket.onopen = null;
      this.socket.onmessage = null;
      this.socket.onclose = null;
      this.socket.onerror = null;
      this.socket.close();
    }
    this.socket = null;
  }

  /**
   * Подписаться на канал.
   * @returns Функция отписки — вызвать при размонтировании компонента.
   */
  subscribe<C extends WsChannel>(
    channel: C,
    listener: (payload: WsPayload<C>) => void,
  ): () => void {
    if (!this.listeners.has(channel)) {
      this.listeners.set(channel, new Set());
    }
    // Внутри хранится стёртый тип; при dispatch payload приведён к нужному типу через канал.
    const erased = listener as AnyListener;
    this.listeners.get(channel)!.add(erased);
    return () => {
      this.listeners.get(channel)?.delete(erased);
    };
  }

  // ── Приватные методы ────────────────────────────────────────────────────────

  private handleRawMessage(raw: unknown): void {
    if (typeof raw !== 'string') return;
    let msg: WsMessage;
    try {
      msg = JSON.parse(raw) as WsMessage;
    } catch {
      return;
    }
    if (typeof msg !== 'object' || msg === null || !('channel' in msg)) return;

    if (msg.channel === 'ping') {
      this.send({ channel: 'pong', payload: { ts: Date.now() } });
      return;
    }

    const handlers = this.listeners.get(msg.channel);
    if (!handlers) return;
    handlers.forEach((h) => h(msg.payload));
  }

  private send(msg: WsMessage): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(msg));
    }
  }

  private startPing(): void {
    this.pingTimer = setInterval(() => {
      this.send({ channel: 'ping', payload: { ts: Date.now() } });
    }, PING_INTERVAL_MS);
  }

  private stopPing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.destroyed || !this.url) return;
    const delay = Math.min(1_000 * 2 ** this.retryCount, BACKOFF_CAP_MS);
    this.retryCount++;
    this.retryTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }
}

/**
 * Синглтон WS-клиента. URL резолвится через {@link resolveWsUrl} — из window.location
 * по умолчанию, с override через window.__env__/VITE_WS_URL при необходимости.
 */
export const wsClient = new WsClient(resolveWsUrl());
