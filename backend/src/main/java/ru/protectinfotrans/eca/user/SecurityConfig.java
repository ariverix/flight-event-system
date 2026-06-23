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

/** Stateless JWT security. CSRF отключён, CORS разрешён для dev-сервера фронта. */
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
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // внешние ACARS-системы и OOOI-источники не имеют JWT —
                        // это ingestion endpoint, аутентификация на уровне сети/VPN
                        .requestMatchers("/api/v1/messages/**").permitAll()
                        .requestMatchers("/api/v1/flights/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/auth/register").hasRole("ADMIN")
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/audit-log/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/sequences/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/v1/executions/**").hasAnyRole("OPERATOR", "ADMIN")
                        // P2-6: DLQ — админская операция оператора над сбойными входящими (ручной
                        // reprocess/discard), НЕ открытый ingestion-путь (тот остаётся /messages/**)
                        .requestMatchers("/api/v1/dlq/**").hasAnyRole("OPERATOR", "ADMIN")
                        // P3-1: CRUD шаблонов сообщений — админ/оператор операция, НЕ открытый
                        // эндпоинт (явное правило ДО catch-all anyRequest().permitAll() ниже)
                        .requestMatchers("/api/v1/templates/**").hasAnyRole("OPERATOR", "ADMIN")
                        // P3-2: CRUD правил извлечения custom fields — тот же принцип, что у
                        // /api/v1/templates/** выше (явное правило ДО catch-all)
                        .requestMatchers("/api/v1/custom-field-rules/**").hasAnyRole("OPERATOR", "ADMIN")
                        // P3-3: обзор активных custom conditions — тот же принцип, что у
                        // /api/v1/custom-field-rules/** выше (явное правило ДО catch-all)
                        .requestMatchers("/api/v1/conditions/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        // статика и SPA — без защиты, закрываем только /api/**
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // нужно для передачи Authorization header — без этого браузер блокирует preflight
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
