package ru.protectinfotrans.eca.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO для self-service смены пароля аутентифицированным пользователем.
 *
 * @param currentPassword текущий пароль (подтверждение владения аккаунтом)
 * @param newPassword новый пароль (минимум 4 символа для MVP — тот же порог, что в {@link RegisterRequest})
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 4, message = "Password must be at least 4 characters")
        String newPassword
) {
}
