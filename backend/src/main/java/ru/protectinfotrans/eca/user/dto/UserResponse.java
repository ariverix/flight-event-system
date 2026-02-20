package ru.protectinfotrans.eca.user.dto;

import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;

import java.time.LocalDateTime;

/**
 * DTO для представления пользователя в ответах API.
 *
 * @param id ID пользователя
 * @param username логин
 * @param fullName полное имя
 * @param role роль
 * @param enabled активен ли пользователь
 * @param createdAt дата создания
 */
public record UserResponse(
        Long id,
        String username,
        String fullName,
        Role role,
        Boolean enabled,
        LocalDateTime createdAt
) {
    /**
     * Маппинг из entity User в DTO UserResponse.
     */
    public static UserResponse fromEntity(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getRole(),
                user.getEnabled(),
                user.getCreatedAt()
        );
    }
}
