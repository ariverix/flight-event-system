/**
 * Модуль User Management — управление пользователями, аутентификация, авторизация.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "User Management",
        allowedDependencies = {"root::"}
)
@org.springframework.modulith.NamedInterface("auth")
package ru.protectinfotrans.eca.user;
