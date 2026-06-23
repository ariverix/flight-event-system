package ru.protectinfotrans.eca.user.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Роль — способ сгруппировать гранулярные {@link Permission} (user-rights, P4-1). Эндпоинты
 * проверяют ИМЕННО права, а не роль; источник истины маппинга роль→права — здесь.
 */
public enum Role {

    /** Мониторинг и операционные действия; НЕ управляет последовательностями и пользователями. */
    OPERATOR(EnumSet.of(
            Permission.VIEW_SEQUENCES,
            Permission.MANAGE_EXECUTIONS,
            Permission.VIEW_TEMPLATES,
            Permission.VIEW_CUSTOM_FIELDS,
            Permission.VIEW_CONDITIONS,
            Permission.MANAGE_EVENT_HANDLING,
            Permission.MANAGE_DLQ)),

    /** Полный доступ: всё, что OPERATOR + управление последовательностями, пользователями, аудитом, системой. */
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }
}
