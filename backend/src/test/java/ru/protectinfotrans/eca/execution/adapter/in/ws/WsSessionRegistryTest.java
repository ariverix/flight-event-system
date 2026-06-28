package ru.protectinfotrans.eca.execution.adapter.in.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты {@link WsSessionRegistry} (P7-4).
 */
class WsSessionRegistryTest {

    private WsSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistry();
    }

    private WebSocketSession mockSession(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        return s;
    }

    @Test
    void registerPending_addsSessionToPendingAndNotAuthenticated() {
        WebSocketSession session = mockSession("s1");
        registry.registerPending(session);

        assertThat(registry.pendingCount()).isEqualTo(1);
        assertThat(registry.authenticatedCount()).isZero();
        assertThat(registry.isAuthenticated(session)).isFalse();
    }

    @Test
    void markAuthenticated_movesFromPendingToAuthenticated() {
        WebSocketSession session = mockSession("s2");
        registry.registerPending(session);
        registry.markAuthenticated(session);

        assertThat(registry.pendingCount()).isZero();
        assertThat(registry.authenticatedCount()).isEqualTo(1);
        assertThat(registry.isAuthenticated(session)).isTrue();
    }

    @Test
    void markAuthenticated_withoutPriorPending_isIdempotent() {
        WebSocketSession session = mockSession("s3");
        registry.markAuthenticated(session);

        assertThat(registry.authenticatedCount()).isEqualTo(1);
        assertThat(registry.isAuthenticated(session)).isTrue();
    }

    @Test
    void remove_removesFromPending() {
        WebSocketSession session = mockSession("s4");
        registry.registerPending(session);
        registry.remove(session);

        assertThat(registry.pendingCount()).isZero();
        assertThat(registry.authenticatedCount()).isZero();
    }

    @Test
    void remove_removesFromAuthenticated() {
        WebSocketSession session = mockSession("s5");
        registry.registerPending(session);
        registry.markAuthenticated(session);
        registry.remove(session);

        assertThat(registry.authenticatedCount()).isZero();
        assertThat(registry.isAuthenticated(session)).isFalse();
    }

    @Test
    void getAuthenticated_returnsOnlyAuthenticatedSessions() {
        WebSocketSession pending = mockSession("pending");
        WebSocketSession auth   = mockSession("auth");

        registry.registerPending(pending);
        registry.registerPending(auth);
        registry.markAuthenticated(auth);

        assertThat(registry.getAuthenticated())
                .containsExactly(auth)
                .doesNotContain(pending);
    }

    @Test
    void multipleSessions_trackedIndependently() {
        WebSocketSession s1 = mockSession("m1");
        WebSocketSession s2 = mockSession("m2");
        WebSocketSession s3 = mockSession("m3");

        registry.registerPending(s1);
        registry.registerPending(s2);
        registry.registerPending(s3);
        registry.markAuthenticated(s1);
        registry.markAuthenticated(s3);

        assertThat(registry.pendingCount()).isEqualTo(1);
        assertThat(registry.authenticatedCount()).isEqualTo(2);
        assertThat(registry.isAuthenticated(s1)).isTrue();
        assertThat(registry.isAuthenticated(s2)).isFalse();
        assertThat(registry.isAuthenticated(s3)).isTrue();
    }
}
