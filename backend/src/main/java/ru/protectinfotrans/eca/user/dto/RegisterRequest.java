package ru.protectinfotrans.eca.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.protectinfotrans.eca.user.domain.Role;

/**
 * DTO для регистрации нового пользователя.
 *
 * @param username логин (уникальный)
 * @param password пароль (минимум 4 символа для MVP)
 * @param fullName полное имя
 * @param role роль (OPERATOR или ADMIN)
 */
public record RegisterRequest(
        @NotBlank(message = "Username is required")
        @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
        String username,

        @NotBlank(message = "Password is required")
        @Size(min = 4, message = "Password must be at least 4 characters")
        String password,

        @NotBlank(message = "Full name is required")
        String fullName,

        @NotNull(message = "Role is required")
        Role role
) {
}
