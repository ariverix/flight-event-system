package ru.protectinfotrans.eca.user;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ru.protectinfotrans.eca.user.auth.JwtAuthenticationFilter;

import java.util.List;

/**
 * Конфигурация Spring Security с JWT-аутентификацией.
 *
 * Настройки:
 * - JWT-based аутентификация (stateless)
 * - CSRF отключен (не требуется для JWT)
 * - CORS для фронтенда (localhost:5173, localhost:3000)
 * - Роли: OPERATOR, ADMIN
 *
 * Правила авторизации:
 * - /api/v1/auth/login — открыт для всех
 * - /api/v1/messages/**, /api/v1/flights/** — открыт для внешних систем (UC-06)
 * - /api/v1/auth/register, /api/v1/users/** — только ADMIN (UC-09)
 * - /api/v1/sequences/**, /api/v1/executions/** — OPERATOR или ADMIN
 * - /actuator/health — открыт (Docker healthcheck)
 * - /actuator/** (кроме /health) — только ADMIN
 * - /swagger-ui/**, /v3/api-docs/** — открыт для всех
 * - Статика и SPA-маршруты — открыты (защита только на API)
 *
 * См. диплом: раздел 1.3.5 (акторы), Глава 2 (технологический стек - Spring Security, JJWT)
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Публичные эндпоинты (без аутентификации)
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/messages/**").permitAll()   // UC-06: Внешние системы
                        .requestMatchers("/api/v1/flights/**").permitAll()    // UC-06: Внешние системы
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()      // Docker healthcheck

                        // Эндпоинты только для ADMIN
                        .requestMatchers("/api/v1/auth/register").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")  // UC-09
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Эндпоинты для OPERATOR и ADMIN
                        .requestMatchers("/api/v1/sequences/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/v1/executions/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/v1/auth/me").authenticated()

                        // Статические ресурсы и SPA-маршруты — открыты (защита только на /api/**)
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS конфигурация для фронтенда.
     * Разрешает запросы с localhost:5173 (Vite dev server) и localhost:3000.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * PasswordEncoder для хеширования паролей (BCrypt).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
