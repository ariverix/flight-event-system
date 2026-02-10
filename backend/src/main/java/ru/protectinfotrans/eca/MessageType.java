package ru.protectinfotrans.eca;

/**
 * Типы сообщений ACARS.
 *
 * См. диплом: раздел 1.1.2
 */
public enum MessageType {

    /** Борт -> земля */
    DOWNLINK,

    /** Земля -> борт */
    UPLINK,

    /** Земля -> земля */
    GROUND
}
