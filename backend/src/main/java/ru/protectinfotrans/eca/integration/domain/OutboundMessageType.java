package ru.protectinfotrans.eca.integration.domain;

/**
 * Тип исходящего сообщения durable-шлюза (P2-3) — паритет с SITA Sequencer ACTION-шагами
 * SEND_UPLINK (борт-земля) / SEND_GROUND (земля-земля, к получателям).
 */
public enum OutboundMessageType {
    UPLINK,
    GROUND
}
