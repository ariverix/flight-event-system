package ru.protectinfotrans.eca.user.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.UserResponse;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Users", description = "Управление пользователями (UC-09, только ADMIN)")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "List users", description = "Get all users (ADMIN only)")
    public ResponseEntity<List<UserResponse>> listUsers() {
        log.info("Fetching all users");

        List<User> users = userService.getAllUsers();
        List<UserResponse> response = users.stream()
                .map(UserResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "Toggle user", description = "Enable or disable user account (ADMIN only)")
    public ResponseEntity<?> toggleUser(@PathVariable Long id, Authentication authentication) {
        log.info("Toggling user: id={}", id);

        try {
            User user = userService.toggleUserEnabled(id, authentication.getName());
            UserResponse response = UserResponse.fromEntity(user);
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.warn("Toggle user failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            log.warn("Toggle user rejected: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
