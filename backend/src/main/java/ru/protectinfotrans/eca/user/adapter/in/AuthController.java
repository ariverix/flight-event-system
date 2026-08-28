package ru.protectinfotrans.eca.user.adapter.in;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import ru.protectinfotrans.eca.LogSanitizer;
import ru.protectinfotrans.eca.user.application.JwtService;
import ru.protectinfotrans.eca.user.application.InvalidRefreshTokenException;
import ru.protectinfotrans.eca.user.application.RefreshTokenService;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.ChangePasswordRequest;
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
    @ApiResponse(responseCode = "429", description = "Превышен лимит попыток входа (rate limit, брутфорс-защита)")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login attempt for username: {}", LogSanitizer.sanitize(request.username()));

        User user = userService.findByUsername(request.username());

        if (user == null) {
            log.warn("Login failed: user '{}' not found", LogSanitizer.sanitize(request.username()));
            auditLoginFailure(request.username(), null, "USER_NOT_FOUND");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        if (!user.getEnabled()) {
            log.warn("Login failed: user '{}' is disabled", LogSanitizer.sanitize(request.username()));
            auditLoginFailure(request.username(), user.getId(), "USER_DISABLED");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "User account is disabled"));
        }

        if (!userService.checkPassword(user, request.password())) {
            log.warn("Login failed: incorrect password for user '{}'", LogSanitizer.sanitize(request.username()));
            auditLoginFailure(request.username(), user.getId(), "BAD_PASSWORD");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid username or password"));
        }

        String token = jwtService.generateToken(user.getUsername(), user.getRole());
        String refreshToken = refreshTokenService.issue(user.getId());

        log.info("Login successful for user: {}, role: {}", LogSanitizer.sanitize(user.getUsername()), user.getRole());

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_LOGIN")
                // P4-5: «кто» — сам вошедший пользователь (actor == entity для логина)
                .userId(user.getId())
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
    @ApiResponse(responseCode = "429", description = "Превышен лимит запросов (rate limit)")
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
    @ApiResponse(responseCode = "429", description = "Превышен лимит запросов (rate limit)")
    @Operation(summary = "Logout", description = "P4-2: инвалидировать refresh-токен.")
    public ResponseEntity<?> logout(@Valid @RequestBody RefreshRequest request) {
        Long userId = refreshTokenService.revoke(request.refreshToken());
        // P4-5: аудит выхода. userId известен, только если токен найден (иначе no-op-logout).
        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_LOGOUT")
                .userId(userId)
                .entityType("USER")
                .entityId(userId)
                .build());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    @Operation(summary = "Register user", description = "Register new user (ADMIN only)")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Registration request for username: {}", LogSanitizer.sanitize(request.username()));

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
            log.warn("Current user not found: {}", LogSanitizer.sanitize(username));
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found"));
        }

        UserResponse response = UserResponse.fromEntity(user);
        return ResponseEntity.ok(response);
    }

    /**
     * Self-service смена пароля аутентифицированным пользователем (backlog из
     * PRODUCTION_READINESS_REPORT.md, раздел "Безопасность" — "эндпоинта смены пароля нет").
     *
     * <p><b>HTTP-метод:</b> {@code PUT} — операция идемпотентно ЗАМЕНЯЕТ существующий под-ресурс
     * "пароль" текущего пользователя (в отличие от login/refresh/logout в этом же контроллере,
     * которые остаются {@code POST} как неидемпотентные действия над сессией/токенами).
     *
     * <p><b>Решение по токенам (revoke all vs keep current session):</b> после успешной смены пароля
     * ВСЕ refresh-токены пользователя отзываются (внутри {@link UserService#changePassword} — в ТОЙ ЖЕ
     * транзакции, что и сама смена пароля, чтобы сбой отзыва не мог оставить пароль уже изменённым, а
     * старые сессии — живыми), новая пара токенов НЕ выдаётся — эндпоинт принимает только access-JWT
     * (Authorization header), а не refresh-токен текущей сессии, поэтому нет возможности узнать, какую
     * именно сессию "оставить в живых". Смена пароля — чувствительное действие (в т.ч. реакция на
     * подозрение компрометации), поэтому по умолчанию безопаснее закрыть ВСЕ сессии, включая текущую
     * (при следующем refresh — 401, потребуется re-login с новым паролем). Уже выданный access-токен
     * продолжит работать до естественного истечения (короткий TTL) — это приемлемый компромисс, не
     * полный logout "здесь и сейчас", но исключает пере-использование старых refresh-токенов после
     * смены секрета.
     */
    @PutMapping("/password")
    @ApiResponse(responseCode = "429", description = "Превышен лимит попыток смены пароля (rate limit, брутфорс-защита)")
    @Operation(summary = "Change password", description = "Self-service смена пароля текущего "
            + "аутентифицированного пользователя. Отзывает все refresh-токены пользователя "
            + "(старые сессии не переживают смену пароля).")
    public ResponseEntity<?> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated"));
        }

        User user = userService.findByUsername(auth.getName());
        if (user == null) {
            log.warn("Password change failed: authenticated user '{}' not found",
                    LogSanitizer.sanitize(auth.getName()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "User not found"));
        }

        try {
            userService.changePassword(user.getId(), request.currentPassword(), request.newPassword());
        } catch (IllegalArgumentException e) {
            log.warn("Password change failed for user '{}': {}",
                    LogSanitizer.sanitize(auth.getName()), e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * P4-5: аудит НЕУДАЧНОЙ попытки входа (безопасность — попытки подбора/доступа к отключённым
     * учёткам). userId может быть null (несуществующий пользователь); username и причина — в деталях.
     */
    private void auditLoginFailure(String username, Long userId, String reason) {
        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_LOGIN_FAILED")
                .entityType("USER")
                .entityId(userId)
                .detailsJson(toJson(Map.of("username", username, "reason", reason)))
                .build());
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
