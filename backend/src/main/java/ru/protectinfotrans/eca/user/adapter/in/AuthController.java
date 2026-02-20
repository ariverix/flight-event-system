package ru.protectinfotrans.eca.user.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.user.application.JwtService;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RegisterRequest;
import ru.protectinfotrans.eca.user.dto.UserResponse;

import java.util.Map;

/**
 * REST-контроллер для аутентификации пользователей.
 * Реализует endpoint'ы для логина, регистрации и получения текущего пользователя.
 *
 * См. диплом: раздел 1.3.5 (UC-09), раздел 1.4.1 (гексагональная архитектура - driving adapter)
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "User authentication and registration")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final ru.protectinfotrans.eca.user.port.out.AuditLogPort auditLogPort;

    /**
     * POST /api/v1/auth/login — Аутентификация пользователя.
     * Доступно без авторизации (permitAll).
     *
     * @return JWT-токен при успехе
     */
    @PostMapping("/login")
    @Operation(summary = "Login", description = "Authenticate user and return JWT token")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for username: {}", request.username());

        User user = userService.findByUsername(request.username());

        if (user == null) {
            log.warn("Login failed: user '{}' not found", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        if (!user.getEnabled()) {
            log.warn("Login failed: user '{}' is disabled", request.username());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User account is disabled"));
        }

        if (!userService.checkPassword(user, request.password())) {
            log.warn("Login failed: incorrect password for user '{}'", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        String token = jwtService.generateToken(user.getUsername(), user.getRole());

        log.info("Login successful for user: {}, role: {}", user.getUsername(), user.getRole());

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_LOGIN")
                .entityType("USER")
                .entityId(user.getId())
                .detailsJson("{\"username\":\"" + user.getUsername() + "\"}")
                .build());

        LoginResponse response = new LoginResponse(
                token,
                user.getUsername(),
                user.getRole(),
                user.getFullName()
        );

        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/auth/register — Регистрация нового пользователя.
     * Доступно только для администраторов (SecurityConfig).
     */
    @PostMapping("/register")
    @Operation(summary = "Register user", description = "Register new user (ADMIN only)")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration request for username: {}", request.username());

        try {
            User user = userService.registerUser(
                    request.username(),
                    request.password(),
                    request.fullName(),
                    request.role()
            );

            UserResponse response = UserResponse.fromEntity(user);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Registration failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/v1/auth/me — Получить информацию о текущем пользователе.
     * Доступно для авторизованных пользователей.
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user", description = "Get information about currently authenticated user")
    public ResponseEntity<?> getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        String username = auth.getName();
        User user = userService.findByUsername(username);

        if (user == null) {
            log.warn("Current user not found: {}", username);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found"));
        }

        UserResponse response = UserResponse.fromEntity(user);
        return ResponseEntity.ok(response);
    }
}
