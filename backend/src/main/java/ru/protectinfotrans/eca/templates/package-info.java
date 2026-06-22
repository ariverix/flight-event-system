/**
 * Модуль Templates Engine — справочник шаблонов сообщений (downlink/uplink/ground,
 * computer-generated|external-user), подстановка переменных, рендеринг в формат канала.
 * CRUD-управление шаблонами (REST, RBAC ADMIN/OPERATOR) + сервис рендеринга, потребляемый
 * execution (ACTION send uplink/ground) и integration (durable outbound доставка).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Templates Engine"
)
package ru.protectinfotrans.eca.templates;
