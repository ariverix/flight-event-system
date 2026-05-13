package ru.protectinfotrans.eca.user.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.protectinfotrans.eca.user.application.JwtService;
import ru.protectinfotrans.eca.user.domain.Role;

import java.io.IOException;
import java.util.List;

/**
 * Фильтр аутентификации на основе JWT-токенов.
 *
 * Извлекает JWT из заголовка Authorization: Bearer <token>.
 * При валидном токене устанавливает SecurityContext с аутентифицированным пользователем.
 *
 * См. диплом: раздел 2.4 (безопасность), Глава 2 (технологический стек - Spring Security)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // Если нет заголовка Authorization или не начинается с "Bearer " — пропускаем
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String token = authHeader.substring(7); // Убираем "Bearer "

            // Валидация токена
            if (!jwtService.isTokenValid(token)) {
                log.warn("Invalid JWT token in request to {}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // Извлечение данных из токена
            String username = jwtService.extractUsername(token);
            Role role = jwtService.extractRole(token);

            // Устанавливаем SecurityContext
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + role.name())
                );

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        username,
                        null,
                        authorities
                );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("Authenticated user: {}, role: {}", username, role);
            }

        } catch (Exception e) {
            log.error("JWT authentication error: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
