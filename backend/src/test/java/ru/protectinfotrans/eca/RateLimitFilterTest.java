package ru.protectinfotrans.eca;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Фаза 3 (прогон апгрейда): unit-тесты {@link RateLimitFilter} — детерминированно, без Spring.
 */
@DisplayName("RateLimitFilter — токен-бакет per IP на /auth и /messages")
class RateLimitFilterTest {

    private SimpleMeterRegistry meterRegistry;
    private RateLimitProperties props;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        props = new RateLimitProperties();
        props.setEnabled(true);
        props.setAuth(new RateLimitProperties.Limit(2, 60));       // 2 логина / 60 c
        props.setMessages(new RateLimitProperties.Limit(3, 60));   // 3 ингеста / 60 c
    }

    private RateLimitFilter filter() {
        return new RateLimitFilter(props, new ObjectMapper(), meterRegistry);
    }

    private HttpServletRequest request(String uri, String method, String ip) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        when(req.getMethod()).thenReturn(method);
        when(req.getRemoteAddr()).thenReturn(ip);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        return req;
    }

    private HttpServletResponse responseCapturing(StringWriter body) {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        try {
            when(resp.getWriter()).thenReturn(new PrintWriter(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return resp;
    }

    private double rejected(String scope) {
        return meterRegistry.get("eca.ratelimit.rejected").tag("scope", scope).counter().count();
    }

    @Test
    @DisplayName("запросы в пределах лимита проходят; превышение → 429 + Problem Details + Retry-After + метрика")
    void authBruteForceBlockedAfterCapacity() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        // 2 разрешённых логина
        for (int i = 0; i < 2; i++) {
            filter.doFilterInternal(request("/api/v1/auth/login", "POST", "10.0.0.1"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // 3-й — отклонён
        StringWriter body = new StringWriter();
        HttpServletResponse resp = responseCapturing(body);
        filter.doFilterInternal(request("/api/v1/auth/login", "POST", "10.0.0.1"), resp, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()); // не увеличилось
        verify(resp).setStatus(429);
        verify(resp).setContentType("application/problem+json");
        verify(resp).setHeader(eq("Retry-After"), org.mockito.ArgumentMatchers.anyString());
        assertThat(body.toString()).contains("Rate limit exceeded").contains("429");
        assertThat(rejected("auth")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("разные IP — независимые бакеты")
    void differentIpsHaveIndependentBuckets() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        // исчерпываем лимит для IP .1
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(request("/api/v1/auth/login", "POST", "10.0.0.1"),
                    responseCapturing(new StringWriter()), chain);
        }
        // IP .2 всё ещё проходит
        filter.doFilterInternal(request("/api/v1/auth/login", "POST", "10.0.0.2"),
                mock(HttpServletResponse.class), chain);

        // 2 успешных для .1 + 1 успешный для .2 = 3 doFilter
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(rejected("auth")).isEqualTo(1.0); // только 3-й запрос .1
    }

    @Test
    @DisplayName("messages и auth — отдельные лимиты (независимые scope)")
    void messagesAndAuthAreSeparateScopes() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        // messages capacity=3 — 4-й отклонён
        for (int i = 0; i < 4; i++) {
            filter.doFilterInternal(request("/api/v1/messages/incoming", "POST", "10.0.0.9"),
                    responseCapturing(new StringWriter()), chain);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(rejected("messages")).isEqualTo(1.0);
        assertThat(rejected("auth")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("нелимитируемый путь (/api/v1/sequences) всегда проходит")
    void nonMatchingPathAlwaysPasses() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 50; i++) {
            filter.doFilterInternal(request("/api/v1/sequences", "GET", "10.0.0.5"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(50)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("P4-6: /auth/password (PUT) лимитируется как AUTH — currentPassword угадываемый, брутфорс-поверхность")
    void authPasswordChangeIsRateLimited() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        // 2 разрешённых попытки смены пароля (auth capacity=2)
        for (int i = 0; i < 2; i++) {
            filter.doFilterInternal(request("/api/v1/auth/password", "PUT", "10.0.0.11"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        // 3-я — отклонена
        StringWriter body = new StringWriter();
        HttpServletResponse resp = responseCapturing(body);
        filter.doFilterInternal(request("/api/v1/auth/password", "PUT", "10.0.0.11"), resp, chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(resp).setStatus(429);
        assertThat(rejected("auth")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("/auth/me не лимитируется (аутентифицирован, часто опрашивается фронтом)")
    void authMeIsNotRateLimited() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 20; i++) {
            filter.doFilterInternal(request("/api/v1/auth/me", "GET", "10.0.0.7"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(20)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("OPTIONS (CORS preflight) не лимитируется")
    void optionsPreflightPasses() throws Exception {
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 20; i++) {
            filter.doFilterInternal(request("/api/v1/auth/login", "OPTIONS", "10.0.0.8"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(20)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("выключен (app.ratelimit.enabled=false, как в тестах) → прозрачный passthrough")
    void disabledIsPassthrough() throws Exception {
        props.setEnabled(false);
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 100; i++) {
            filter.doFilterInternal(request("/api/v1/auth/login", "POST", "10.0.0.3"),
                    mock(HttpServletResponse.class), chain);
        }
        verify(chain, times(100)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("X-Forwarded-For учитывается ТОЛЬКО от доверенного прокси; ключ = правый непроверенный хоп")
    void usesXForwardedForOnlyFromTrustedProxy() throws Exception {
        props.setTrustedProxies(java.util.List.of("10.0.0.0/8")); // прокси-сеть доверенная
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req.getMethod()).thenReturn("POST");
        when(req.getRemoteAddr()).thenReturn("10.0.0.100"); // доверенный прокси
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.100");

        // ключ = 203.0.113.7 (правый непроверенный хоп; 10.0.0.100 — доверенный, пропущен)
        filter.doFilterInternal(req, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(req, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(req, responseCapturing(new StringWriter()), chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(rejected("auth")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Multi-hop XFF: несколько доверенных прокси подряд — ключ = реальный клиент, а не промежуточный хоп")
    void usesRealClientHopThroughMultipleTrustedProxies() throws Exception {
        // цепочка из ДВУХ доверенных прокси (напр. внутренний LB + edge-прокси) перед app
        props.setTrustedProxies(java.util.List.of("10.0.0.0/8"));
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req.getMethod()).thenReturn("POST");
        when(req.getRemoteAddr()).thenReturn("10.0.0.100"); // ближайший доверенный прокси
        // реальный клиент добавлен первым доверенным прокси, второй дописал свой хоп справа
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.50, 10.0.0.100");

        // ключ = 203.0.113.7 (оба доверенных хопа справа пропущены) — тот же бакет, что и
        // в двухзвенном случае: лимит (auth capacity=2) считается по реальному клиенту
        filter.doFilterInternal(req, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(req, mock(HttpServletResponse.class), chain);
        filter.doFilterInternal(req, responseCapturing(new StringWriter()), chain);

        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(rejected("auth")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SECURITY: XFF от НЕдоверенного источника игнорируется — спуфинг не обходит лимит")
    void ignoresSpoofedXForwardedForFromUntrustedSource() throws Exception {
        // trustedProxies пуст (дефолт) → XFF не доверяем, ключ всегда remoteAddr
        RateLimitFilter filter = filter();
        FilterChain chain = mock(FilterChain.class);

        // атакующий с одного remoteAddr шлёт РАЗНЫЕ поддельные XFF на каждый запрос
        for (int i = 0; i < 5; i++) {
            HttpServletRequest req = mock(HttpServletRequest.class);
            when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
            when(req.getMethod()).thenReturn("POST");
            when(req.getRemoteAddr()).thenReturn("198.51.100.50"); // один и тот же TCP-peer
            when(req.getHeader("X-Forwarded-For")).thenReturn("10.0.0." + i); // поддельный, разный
            filter.doFilterInternal(req, responseCapturing(new StringWriter()), chain);
        }

        // все 5 легли в ОДИН бакет (по remoteAddr) → 2 прошли, 3 отклонены (auth capacity=2)
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(rejected("auth")).isEqualTo(3.0);
    }
}
