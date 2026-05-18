package ru.protectinfotrans.eca;

/** Типы сообщений ACARS: направление передачи данных */
public enum MessageType {
    DOWNLINK,  // борт -> земля
    UPLINK,    // земля -> борт
    GROUND     // земля -> земля
}
