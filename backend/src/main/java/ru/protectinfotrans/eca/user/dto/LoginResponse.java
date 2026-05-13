package ru.protectinfotrans.eca.user.dto;

import ru.protectinfotrans.eca.user.domain.Role;

/**
 * DTO для ответа при успешной аутентификации.
 *
 * @param token JWT-токен
 * @param username логин пользователя
 * @param role роль пользователя
 * @param fullName полное имя
 */
public record LoginResponse(
        String token,
        String username,
        Role role,
        String fullName
) {
}
