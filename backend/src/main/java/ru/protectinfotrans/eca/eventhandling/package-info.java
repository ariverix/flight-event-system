/**
 * Модуль Event Handling &amp; Notifications (P3-4) — паритет с SITA Sequencer: папки
 * последовательностей, обработчики событий уровня папки (наследуются) и уровня последовательности
 * (переопределяют), Notify-каналы email/webhook с идемпотентной доставкой уведомлений на
 * success/false шага.
 *
 * <p><b>Границы Modulith:</b> модуль — ПОТРЕБИТЕЛЬ событий движка, не вызывается другими модулями
 * напрямую (нет внешних потребителей его {@code port.in} — поэтому {@code @NamedInterface} ему не
 * нужен, в отличие от {@code conditions}/{@code customfields}). Единственная межмодульная
 * зависимость — слушает экспортированное {@link ru.protectinfotrans.eca.execution.event.StepNotificationEvent}
 * ({@code @NamedInterface("event")}) через {@code @ApplicationModuleListener} (тот же приём и тот же
 * источник события, что у {@code integration.NotificationEventListener}). Принадлежность
 * последовательности папке хранится как простой nullable {@code folder_id} в таблице
 * {@code sequences} (модуль {@code sequence}) — это поле {@code Long}, НЕ JPA-связь на
 * {@code Folder}, поэтому Java-зависимости {@code sequence → eventhandling} нет; immediate
 * {@code folderId} приходит в самом событии, дерево папок модуль обходит сам.
 *
 * <p><b>Идемпотентность доставки</b> (закрывает явный TODO ADR-0002 / {@code integration.
 * NotificationEventListener}): durable реестр {@code notification_deliveries} с UNIQUE по
 * естественному дедуп-ключу {@code (executionId, stepIndex, result, handlerId)} — at-least-once
 * republish события не даёт повторного дёрганья канала.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Event Handling & Notifications"
)
package ru.protectinfotrans.eca.eventhandling;
