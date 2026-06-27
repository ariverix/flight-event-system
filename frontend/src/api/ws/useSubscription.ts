/**
 * useSubscription — React-хук для подписки на WebSocket-канал.
 *
 * Использование (P7-4):
 *   const status = useSubscription('instance-status', (payload) => {
 *     setInstances(prev => updateInstance(prev, payload));
 *   });
 *
 * Если WS недоступен — хук молча не делает ничего (нет-оп).
 * Отписка происходит автоматически при размонтировании компонента.
 */
import { useEffect } from 'react';
import { wsClient } from './WsClient';
import type { WsChannel, WsPayload } from './types';

/**
 * @param channel  Имя канала (например 'instance-status').
 * @param listener Обработчик payload; должен быть стабилен (useCallback или из ref).
 * @param enabled  Если false — подписка не создаётся (для условного включения).
 */
export function useSubscription<C extends WsChannel>(
  channel: C,
  listener: (payload: WsPayload<C>) => void,
  enabled = true,
): void {
  useEffect(() => {
    if (!enabled) return;
    const unsubscribe = wsClient.subscribe(channel, listener);
    return unsubscribe;
  }, [channel, listener, enabled]);
}
