package ru.protectinfotrans.eca.execution.adapter.in.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Реестр WebSocket-сессий (P7-4).
 *
 * <p>Две очереди:
 * <ul>
 *   <li>{@code pending} — сессии, ожидающие аутентификации (auth-сообщение ещё не получено)</li>
 *   <li>{@code authenticated} — сессии, прошедшие проверку JWT</li>
 * </ul>
 *
 * <p>Thread-safe: операции над {@link ConcurrentHashMap}-backed Set'ами атомарны.
 */
@Component
public class WsSessionRegistry {

    private final Set<WebSocketSession> pending       = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketSession> authenticated = ConcurrentHashMap.newKeySet();

    /** Регистрирует новую сессию как ожидающую аутентификации. */
    public void registerPending(WebSocketSession session) {
        pending.add(session);
    }

    /**
     * Переводит сессию из pending → authenticated.
     * Если сессии не было в pending — просто добавляет в authenticated (idempotent).
     */
    public void markAuthenticated(WebSocketSession session) {
        pending.remove(session);
        authenticated.add(session);
    }

    /** Полностью удаляет сессию из обеих очередей (при закрытии соединения). */
    public void remove(WebSocketSession session) {
        pending.remove(session);
        authenticated.remove(session);
    }

    /** Возвращает snapshot коллекции аутентифицированных сессий (для broadcast). */
    public Collection<WebSocketSession> getAuthenticated() {
        return authenticated;
    }

    /** Возвращает true, если сессия уже прошла аутентификацию. */
    public boolean isAuthenticated(WebSocketSession session) {
        return authenticated.contains(session);
    }

    /** Для тестов и метрик: количество pending-сессий. */
    public int pendingCount() {
        return pending.size();
    }

    /** Для тестов и метрик: количество authenticated-сессий. */
    public int authenticatedCount() {
        return authenticated.size();
    }
}
