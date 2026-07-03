package ru.protectinfotrans.eca.user;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
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
import ru.protectinfotrans.eca.RateLimitFilter;
import ru.protectinfotrans.eca.user.auth.JwtAuthenticationFilter;

import java.util.List;

/** Stateless JWT security. CSRF отключён, CORS настраивается через app.cors.allowed-origins. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    // Фаза 3: rate limiting (брутфорс /auth, флуд /messages). Пассивен при app.ratelimit.enabled=false.
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                // CORS использует бин corsConfigurationSource (origins из app.cors.allowed-origins)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // P4-1: авторизация по гранулярным user-rights (authorities), а не по роли.
                // Роль раскрывается в права в JwtAuthenticationFilter (Role.getPermissions()).
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // P4-2: refresh/logout аутентифицируются самим refresh-токеном (в теле), не access-JWT
                        .requestMatchers("/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // внешние ACARS-системы и OOOI-источники не имеют JWT —
                        // это ingestion endpoint, аутентификация на уровне сети/VPN
                        .requestMatchers("/api/v1/messages/**").permitAll()
                        .requestMatchers("/api/v1/flights/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // P5-3: Kubernetes health probes — без аутентификации (k8s-пробам JWT недоступен).
                        // Только специфичные probe-пути; полный /actuator/health и остальной /actuator/**
                        // остаются за RBAC SYSTEM_ADMIN (правило ниже).
                        .requestMatchers(
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                "/actuator/health/startup"
                        ).permitAll()
                        .requestMatchers("/api/v1/auth/register").hasAuthority("MANAGE_USERS")
                        .requestMatchers("/api/v1/users/**").hasAuthority("MANAGE_USERS")
                        .requestMatchers("/api/v1/audit-log/**").hasAuthority("VIEW_AUDIT_LOG")
                        .requestMatchers("/actuator/**").hasAuthority("SYSTEM_ADMIN")
                        // путь требует право чтения; запись (POST/PUT/DELETE) дополнительно гейтится
                        // @PreAuthorize('MANAGE_SEQUENCES') на методах контроллера
                        .requestMatchers("/api/v1/sequences/**").hasAuthority("VIEW_SEQUENCES")
                        .requestMatchers("/api/v1/executions/**").hasAuthority("MANAGE_EXECUTIONS")
                        // P2-6: DLQ — ручной reprocess/discard сбойных входящих, НЕ открытый
                        // ingestion-путь (тот остаётся /messages/**)
                        .requestMatchers("/api/v1/dlq/**").hasAuthority("MANAGE_DLQ")
                        // путь — право чтения; запись дополнительно гейтится @PreAuthorize MANAGE_* на методах
                        .requestMatchers("/api/v1/templates/**").hasAuthority("VIEW_TEMPLATES")
                        .requestMatchers("/api/v1/custom-field-rules/**").hasAuthority("VIEW_CUSTOM_FIELDS")
                        .requestMatchers("/api/v1/conditions/**").hasAuthority("VIEW_CONDITIONS")
                        .requestMatchers("/api/v1/folders/**").hasAuthority("MANAGE_EVENT_HANDLING")
                        .requestMatchers("/api/v1/event-handlers/**").hasAuthority("MANAGE_EVENT_HANDLING")
                        .requestMatchers("/api/v1/auth/me").authenticated()
                        // P4-1 (закрытие backlog P0-3): default-DENY для всех прочих /api/** —
                        // будущий контроллер без явного matcher НЕ окажется открытым (раньше
                        // цепочка заканчивалась anyRequest().permitAll() = default-allow).
                        .requestMatchers("/api/**").authenticated()
                        // статика и SPA — без защиты (это уже не /api/**)
                        .anyRequest().permitAll()
                )
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Фаза 3: rate limiter ПЕРЕД JWT-фильтром (и после CorsFilter Spring Security —
                // 429 сохраняет CORS-заголовки для браузера). Брутфорс/флуд отсекается до аутентификации.
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    /**
     * Фаза 3: {@link RateLimitFilter} — {@code @Component}, поэтому Spring Boot иначе
     * зарегистрировал бы его И в servlet-цепочке (глобально, до Spring Security). Отключаем эту
     * авто-регистрацию — фильтр работает ТОЛЬКО там, где он добавлен в security-цепочку выше
     * (после CorsFilter). Без этого он выполнялся бы дважды и до применения CORS.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            // Фаза 3: origins вынесены в конфиг (раньше — хардкод dev-значений). Прод задаёт реальные
            // origins через env APP_CORS_ALLOWED_ORIGINS (список через запятую); dev-дефолт — фронт vite/CRA.
            @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
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
