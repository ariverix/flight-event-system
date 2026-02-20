package ru.protectinfotrans.eca.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO для запроса аутентификации.
 *
 * @param username логин пользователя
 * @param password пароль
 */
public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password
) {
}
