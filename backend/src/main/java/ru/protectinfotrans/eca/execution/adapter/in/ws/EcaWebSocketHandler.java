package ru.protectinfotrans.eca.execution.adapter.in.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.protectinfotrans.eca.TokenValidatorPort;

import java.io.IOException;
import java.util.Map;

/**
 * Spring WebSocket handler для эндпоинта {@code /ws/eca} (P7-4).
 *
 * <p>Протокол (ADR-0005 п.4):
 * <ol>
 *   <li>Клиент подключается по ws(s)://host/ws/eca — без JWT в URL.</li>
 *   <li>Первым сообщением клиент отправляет: {@code {"channel":"auth","payload":{"token":"<jwt>"}}}.</li>
 *   <li>Если JWT валиден — сессия переводится в authenticated; backend начинает рассылать
 *       {@code instance-status} и {@code event-log} события.</li>
 *   <li>Если JWT невалиден — сессия закрывается с {@code CloseStatus.POLICY_VIOLATION}.</li>
 *   <li>Ping/pong: {@code {"channel":"ping","payload":{"ts":...}}} → ответ {@code {"channel":"pong",...}}.</li>
 * </ol>
 *
 * <p>Сессия до аутентификации остаётся в {@link WsSessionRegistry} в pending-очереди;
 * не аутентифицированные сессии НЕ получают broadcast-сообщений.
 */
@RequiredArgsConstructor
@Slf4j
public class EcaWebSocketHandler extends TextWebSocketHandler {

    private final WsSessionRegistry registry;
    private final TokenValidatorPort tokenValidator;
    private final ObjectMapper       objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("WS session connected: {}", session.getId());
        registry.registerPending(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("WS: malformed JSON from session {}", session.getId());
            return;
        }

        String channel = root.path("channel").asText(null);
        if (channel == null) {
            log.warn("WS: missing channel from session {}", session.getId());
            return;
        }

        switch (channel) {
            case "auth" -> handleAuth(session, root.path("payload"));
            case "ping" -> handlePing(session, root.path("payload"));
            default -> log.debug("WS: unknown channel '{}' from session {}", channel, session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("WS session closed: {} status={}", session.getId(), status);
        registry.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error for session {}: {}", session.getId(), exception.getMessage());
        registry.remove(session);
    }

    // ── Приватные методы ────────────────────────────────────────────────────────

    private void handleAuth(WebSocketSession session, JsonNode payload) throws IOException {
        if (registry.isAuthenticated(session)) {
            // идемпотентно — повторный auth-кадр игнорируется
            return;
        }

        String token = payload.path("token").asText(null);
        if (token == null || token.isBlank()) {
            log.warn("WS: auth without token from session {} — closing", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        if (!tokenValidator.isValid(token)) {
            log.warn("WS: invalid/expired JWT from session {} — closing", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            registry.remove(session);
            return;
        }

        registry.markAuthenticated(session);
        log.info("WS session authenticated: {}", session.getId());

        // Подтверждение аутентификации (клиент может использовать для обновления UI-индикатора)
        String ack = objectMapper.writeValueAsString(
                Map.of("channel", "auth-ok", "payload", Map.of("sessionId", session.getId())));
        synchronized (session) {
            session.sendMessage(new TextMessage(ack));
        }
    }

    private void handlePing(WebSocketSession session, JsonNode payload) throws IOException {
        if (!registry.isAuthenticated(session)) {
            return;
        }
        long ts = payload.path("ts").asLong(System.currentTimeMillis());
        String pong = objectMapper.writeValueAsString(
                Map.of("channel", "pong", "payload", Map.of("ts", ts)));
        synchronized (session) {
            session.sendMessage(new TextMessage(pong));
        }
    }
}
