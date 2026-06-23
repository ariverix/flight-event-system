package ru.protectinfotrans.eca.user.domain;

/**
 * Гранулярные user-rights (P4-1, паритет SITA Sequencer «user rights»: Manage sequences, edit
 * Aircraft и т.п.). Роль раскрывается в набор прав ({@link Role#getPermissions()}), права
 * выдаются как Spring Security authorities в {@code JwtAuthenticationFilter}, а эндпоинты
 * проверяют ИМЕННО право ({@code hasAuthority('MANAGE_SEQUENCES')}), а не роль — роль остаётся
 * лишь способом сгруппировать права.
 *
 * <p>Каталог сознательно привязан к реально существующим эндпоинтам системы (а не «на будущее») —
 * минимум абстракций, как требует CLAUDE.md. Расширение до конфигурируемых per-role прав в БД —
 * возможный follow-up (сейчас маппинг роль→права кодовый, источник истины — {@link Role}).
 */
public enum Permission {

    /** Просмотр последовательностей и их шагов (read-only). */
    VIEW_SEQUENCES,
    /** Создание/редактирование/активация последовательностей и шагов («Manage sequences»). */
    MANAGE_SEQUENCES,
    /** Просмотр и управление запущенными экземплярами выполнения. */
    MANAGE_EXECUTIONS,
    /** Просмотр шаблонов сообщений (list/get/render). */
    VIEW_TEMPLATES,
    /** Создание/изменение/удаление шаблонов сообщений. */
    MANAGE_TEMPLATES,
    /** Просмотр правил извлечения custom fields. */
    VIEW_CUSTOM_FIELDS,
    /** Создание/изменение/удаление правил извлечения custom fields. */
    MANAGE_CUSTOM_FIELDS,
    /** Просмотр активных custom conditions. */
    VIEW_CONDITIONS,
    /** Папки и обработчики событий (event handling). */
    MANAGE_EVENT_HANDLING,
    /** DLQ: ручной reprocess/discard сбойных входящих. */
    MANAGE_DLQ,
    /** Управление пользователями и регистрация. */
    MANAGE_USERS,
    /** Просмотр журнала аудита. */
    VIEW_AUDIT_LOG,
    /** Системное администрирование (actuator и пр.). */
    SYSTEM_ADMIN
}
