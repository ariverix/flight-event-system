package ru.protectinfotrans.eca.integration.domain;

/**
 * P2-6: состояния circuit breaker на внешний канал доставки (по {@link OutboundMessageType} —
 * UPLINK/GROUND могут идти через разные физические каналы ACARS/AFTN).
 *
 * <p>{@code CLOSED}: нормальная работа, попытки доставки проходят как обычно. {@code OPEN}:
 * серия сбоев превысила порог — канал считается "мёртвым", дальнейшие попытки доставки
 * блокируются fail-fast (без обращения к {@code simulateChannelSend}) до истечения таймаута
 * восстановления, защищая канал/ресурсы от долбёжки. {@code HALF_OPEN}: таймаут восстановления
 * истёк — ОДНА пробная попытка доставки разрешена; успех -> {@code CLOSED} (сброс счётчика
 * сбоев), сбой -> снова {@code OPEN} (новый таймаут).
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
