package ru.protectinfotrans.eca.execution.adapter.in.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты {@link EcaWsBroadcaster} (P7-4).
 *
 * Проверяем:
 *  - broadcast отправляет JSON всем открытым аутентифицированным сессиям
 *  - закрытые сессии удаляются из реестра без ошибки
 *  - ошибки sendMessage логируются и сессия удаляется
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EcaWsBroadcasterTest {

    @Mock WsSessionRegistry registry;

    EcaWsBroadcaster broadcaster;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        broadcaster = new EcaWsBroadcaster(registry, objectMapper);
    }

    private WebSocketSession openSession(String id) throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    private WebSocketSession closedSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(false);
        return s;
    }

    @Test
    void broadcast_sendsJsonToAllOpenSessions() throws Exception {
        WebSocketSession s1 = openSession("s1");
        WebSocketSession s2 = openSession("s2");
        when(registry.getAuthenticated()).thenReturn(List.of(s1, s2));

        broadcaster.broadcast("instance-status", Map.of("instanceId", 1L, "status", "RUNNING"));

        ArgumentCaptor<TextMessage> captor1 = ArgumentCaptor.forClass(TextMessage.class);
        ArgumentCaptor<TextMessage> captor2 = ArgumentCaptor.forClass(TextMessage.class);
        verify(s1).sendMessage(captor1.capture());
        verify(s2).sendMessage(captor2.capture());

        assertThat(captor1.getValue().getPayload())
                .contains("\"channel\":\"instance-status\"")
                .contains("RUNNING");
        assertThat(captor2.getValue().getPayload())
                .contains("\"channel\":\"instance-status\"");
    }

    @Test
    void broadcast_closedSession_removedFromRegistry() throws Exception {
        WebSocketSession closed = closedSession("closed");
        when(registry.getAuthenticated()).thenReturn(List.of(closed));

        broadcaster.broadcast("instance-status", Map.of("status", "RUNNING"));

        verify(registry).remove(closed);
        verify(closed, never()).sendMessage(any());
    }

    @Test
    void broadcast_sendMessageFails_sessionRemovedFromRegistry() throws Exception {
        WebSocketSession failing = openSession("failing");
        when(registry.getAuthenticated()).thenReturn(List.of(failing));
        doThrow(new java.io.IOException("simulated send error"))
                .when(failing).sendMessage(any());

        broadcaster.broadcast("event-log", Map.of("id", 42L));

        verify(registry).remove(failing);
    }

    @Test
    void broadcast_noSessions_doesNotThrow() {
        when(registry.getAuthenticated()).thenReturn(List.of());

        broadcaster.broadcast("instance-status", Map.of());
        // Не должно бросать исключений
    }
}
