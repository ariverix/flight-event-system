package ru.protectinfotrans.eca.user.dto;

import ru.protectinfotrans.eca.user.domain.Role;

/**
 * DTO для ответа при успешной аутентификации/обновлении токена.
 *
 * @param token короткоживущий access JWT
 * @param refreshToken долгоживущий refresh-токен (ротируется при каждом обновлении, P4-2)
 * @param username логин пользователя
 * @param role роль пользователя
 * @param fullName полное имя
 */
public record LoginResponse(
        String token,
        String refreshToken,
        String username,
        Role role,
        String fullName
) {
}
