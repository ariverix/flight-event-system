package ru.protectinfotrans.eca.user.dto;

import jakarta.validation.constraints.NotBlank;

/** Запрос обновления токенов по refresh-токену (P4-2). */
public record RefreshRequest(
        @NotBlank(message = "refreshToken обязателен")
        String refreshToken
) {}
