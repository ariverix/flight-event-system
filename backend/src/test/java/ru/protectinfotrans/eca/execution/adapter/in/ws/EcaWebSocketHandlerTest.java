package ru.protectinfotrans.eca.execution.adapter.in.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import ru.protectinfotrans.eca.TokenValidatorPort;

import java.io.IOException;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты {@link EcaWebSocketHandler} (P7-4).
 *
 * Проверяем:
 *  - afterConnectionEstablished: регистрирует сессию как pending
 *  - auth: валидный JWT → markAuthenticated + ack
 *  - auth: невалидный JWT → close POLICY_VIOLATION
 *  - auth: пустой token → close POLICY_VIOLATION
 *  - ping: аутентифицированная сессия → pong
 *  - ping: неаутентифицированная сессия → игнор
 *  - afterConnectionClosed: remove из реестра
 *  - handleTransportError: remove из реестра
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EcaWebSocketHandlerTest {

    @Mock WsSessionRegistry   registry;
    @Mock TokenValidatorPort   tokenValidator;
    @Mock WebSocketSession     session;

    EcaWebSocketHandler handler;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        handler = new EcaWebSocketHandler(registry, tokenValidator, objectMapper);
        when(session.getId()).thenReturn("test-session");
        when(session.isOpen()).thenReturn(true);
    }

    // ── afterConnectionEstablished ─────────────────────────────────────────────

    @Test
    void afterConnectionEstablished_registersAsPending() throws Exception {
        handler.afterConnectionEstablished(session);
        verify(registry).registerPending(session);
    }

    // ── auth: валидный JWT ─────────────────────────────────────────────────────

    @Test
    void handleAuth_validToken_authenticatesAndSendsAck() throws Exception {
        String payload = """
                {"channel":"auth","payload":{"token":"valid-token"}}
                """;
        when(tokenValidator.isValid("valid-token")).thenReturn(true);
        when(registry.isAuthenticated(session)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(registry).markAuthenticated(session);

        // Проверяем, что auth-ok отправлен
        ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(msgCaptor.capture());
        String ack = msgCaptor.getValue().getPayload();
        assertThat(ack).contains("auth-ok");
    }

    // ── auth: невалидный JWT ───────────────────────────────────────────────────

    @Test
    void handleAuth_invalidToken_closesWithPolicyViolation() throws Exception {
        String payload = """
                {"channel":"auth","payload":{"token":"bad-token"}}
                """;
        when(tokenValidator.isValid("bad-token")).thenReturn(false);
        when(registry.isAuthenticated(session)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(registry, never()).markAuthenticated(session);
    }

    // ── auth: пустой token ─────────────────────────────────────────────────────

    @Test
    void handleAuth_emptyToken_closesWithPolicyViolation() throws Exception {
        String payload = """
                {"channel":"auth","payload":{"token":""}}
                """;
        when(registry.isAuthenticated(session)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(tokenValidator, never()).isValid(any());
    }

    // ── auth: повторный вызов → idempotent ────────────────────────────────────

    @Test
    void handleAuth_alreadyAuthenticated_isIdempotent() throws Exception {
        String payload = """
                {"channel":"auth","payload":{"token":"some-token"}}
                """;
        when(registry.isAuthenticated(session)).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(tokenValidator, never()).isValid(any());
        verify(registry, never()).markAuthenticated(any());
    }

    // ── ping / pong ────────────────────────────────────────────────────────────

    @Test
    void handlePing_authenticatedSession_sendsPong() throws Exception {
        String payload = """
                {"channel":"ping","payload":{"ts":12345}}
                """;
        when(registry.isAuthenticated(session)).thenReturn(true);

        handler.handleTextMessage(session, new TextMessage(payload));

        ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(msgCaptor.capture());
        String pong = msgCaptor.getValue().getPayload();
        assertThat(pong).contains("pong");
        assertThat(pong).contains("12345");
    }

    @Test
    void handlePing_notAuthenticated_isIgnored() throws Exception {
        String payload = """
                {"channel":"ping","payload":{"ts":12345}}
                """;
        when(registry.isAuthenticated(session)).thenReturn(false);

        handler.handleTextMessage(session, new TextMessage(payload));

        verify(session, never()).sendMessage(any());
    }

    // ── afterConnectionClosed ─────────────────────────────────────────────────

    @Test
    void afterConnectionClosed_removesFromRegistry() throws Exception {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        verify(registry).remove(session);
    }

    // ── handleTransportError ───────────────────────────────────────────────────

    @Test
    void handleTransportError_removesFromRegistry() throws Exception {
        handler.handleTransportError(session, new IOException("simulated error"));
        verify(registry).remove(session);
    }

    // ── malformed JSON ─────────────────────────────────────────────────────────

    @Test
    void handleTextMessage_malformedJson_doesNothing() throws Exception {
        handler.handleTextMessage(session, new TextMessage("not json"));
        verify(registry, never()).markAuthenticated(any());
        verify(session, never()).sendMessage(any());
    }
}
