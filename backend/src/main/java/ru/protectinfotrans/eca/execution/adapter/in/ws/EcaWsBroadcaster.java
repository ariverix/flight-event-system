package ru.protectinfotrans.eca.execution.adapter.in.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

/**
 * Широковещательная рассылка WS-сообщений всем аутентифицированным сессиям (P7-4).
 *
 * <p>Формат сообщений — тот же JSON-конверт, что использует frontend WsClient:
 * <pre>{ "channel": "...", "payload": { ... } }</pre>
 *
 * <p>Методы sinchronized на уровне {@link WebSocketSession}: одна сессия может принимать
 * сообщения только последовательно (WebSocketSession#sendMessage не thread-safe).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EcaWsBroadcaster {

    private final WsSessionRegistry registry;
    private final ObjectMapper       objectMapper;

    /**
     * Рассылает сообщение на указанном канале всем аутентифицированным сессиям.
     *
     * @param channel имя канала (например {@code "instance-status"}, {@code "event-log"})
     * @param payload произвольный объект, сериализуемый в JSON
     */
    public void broadcast(String channel, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of("channel", channel, "payload", payload));
        } catch (IOException e) {
            log.error("Failed to serialize WS broadcast payload for channel={}: {}", channel, e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : registry.getAuthenticated()) {
            if (!session.isOpen()) {
                registry.remove(session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("Failed to send WS message to session {}: {}", session.getId(), e.getMessage());
                registry.remove(session);
            }
        }
    }
}
