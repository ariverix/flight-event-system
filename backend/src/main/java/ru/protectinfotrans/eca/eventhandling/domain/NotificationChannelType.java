package ru.protectinfotrans.eca.eventhandling.domain;

/** Канал доставки уведомления (P3-4): email или webhook (HTTP POST). */
public enum NotificationChannelType {
    EMAIL,
    WEBHOOK
}
