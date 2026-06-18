package ru.protectinfotrans.eca.user.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.protectinfotrans.eca.user.application.JwtService;
import ru.protectinfotrans.eca.user.domain.Role;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для JwtAuthenticationFilter.
 * Проверяет извлечение JWT из заголовка Authorization и заполнение SecurityContext.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("должен пропустить запрос без заголовка Authorization")
    void shouldSkipWhenNoAuthHeader() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).isTokenValid(anyString());
    }

    @Test
    @DisplayName("должен пропустить запрос если заголовок не начинается с Bearer")
    void shouldSkipWhenHeaderNotBearer() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).isTokenValid(anyString());
    }

    @Test
    @DisplayName("должен установить аутентификацию для валидного токена")
    void shouldAuthenticateOnValidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtService.extractUsername("valid-token")).thenReturn("operator1");
        when(jwtService.extractRole("valid-token")).thenReturn(Role.OPERATOR);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("operator1");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_OPERATOR");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("должен установить роль ADMIN корректно")
    void shouldAuthenticateAdminRole() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer admin-token");
        when(jwtService.isTokenValid("admin-token")).thenReturn(true);
        when(jwtService.extractUsername("admin-token")).thenReturn("admin1");
        when(jwtService.extractRole("admin-token")).thenReturn(Role.ADMIN);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("не должен устанавливать аутентификацию для невалидного токена")
    void shouldNotAuthenticateOnInvalidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(jwtService.isTokenValid("invalid-token")).thenReturn(false);
        when(request.getRequestURI()).thenReturn("/api/v1/sequences");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).extractUsername(anyString());
    }

    @Test
    @DisplayName("не должен перезаписывать существующую аутентификацию")
    void shouldNotOverwriteExistingAuthentication() throws Exception {
        Authentication existing = mock(Authentication.class);
        SecurityContextHolder.getContext().setAuthentication(existing);

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.isTokenValid("valid-token")).thenReturn(true);
        when(jwtService.extractUsername("valid-token")).thenReturn("operator1");
        when(jwtService.extractRole("valid-token")).thenReturn(Role.OPERATOR);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("должен продолжить цепочку фильтров при исключении в обработке токена")
    void shouldContinueChainOnException() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer broken-token");
        when(jwtService.isTokenValid("broken-token")).thenThrow(new RuntimeException("boom"));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
