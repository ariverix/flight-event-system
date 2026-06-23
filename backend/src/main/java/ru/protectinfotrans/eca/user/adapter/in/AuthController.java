package ru.protectinfotrans.eca.user.adapter.in;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ru.protectinfotrans.eca.user.application.InvalidRefreshTokenException;
import ru.protectinfotrans.eca.user.application.RefreshTokenService;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RefreshRequest;
import ru.protectinfotrans.eca.user.dto.RegisterRequest;
import ru.protectinfotrans.eca.user.dto.UserResponse;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "User authentication and registration")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final ru.protectinfotrans.eca.user.port.out.AuditLogPort auditLogPort;
    private final ObjectMapper objectMapper;

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
        String refreshToken = refreshTokenService.issue(user.getId());

        log.info("Login successful for user: {}, role: {}", user.getUsername(), user.getRole());

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_LOGIN")
                .entityType("USER")
                .entityId(user.getId())
                .detailsJson(toJson(Map.of("username", user.getUsername())))
                .build());

        LoginResponse response = new LoginResponse(
                token,
                refreshToken,
                user.getUsername(),
                user.getRole(),
                user.getFullName()
        );

        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh tokens",
               description = "P4-2: обменять refresh-токен на новую пару (access + новый refresh, ротация). "
                       + "Старый refresh инвалидируется; reuse отозванного → 401 и отзыв всех токенов пользователя.")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest request) {
        try {
            RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken());
            User user = userService.findById(rotation.userId());
            if (user == null || !user.getEnabled()) {
                refreshTokenService.revoke(rotation.refreshToken());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "User unavailable"));
            }
            String access = jwtService.generateToken(user.getUsername(), user.getRole());
            return ResponseEntity.ok(new LoginResponse(
                    access, rotation.refreshToken(), user.getUsername(), user.getRole(), user.getFullName()));
        } catch (InvalidRefreshTokenException e) {
            log.warn("Refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid refresh token"));
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "P4-2: инвалидировать refresh-токен.")
    public ResponseEntity<?> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revoke(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit details", e);
            return null;
        }
    }
}
