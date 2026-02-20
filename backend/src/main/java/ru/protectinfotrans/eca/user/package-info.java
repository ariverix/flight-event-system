/**
 * Модуль User Management — управление пользователями, аутентификация, авторизация.
 * См. диплом: раздел 1.4.2, таблица 1.5
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "User Management",
        allowedDependencies = {"root::"}
)
@org.springframework.modulith.NamedInterface("auth")
package ru.protectinfotrans.eca.user;
