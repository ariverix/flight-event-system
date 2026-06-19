package ru.protectinfotrans.eca;

import org.slf4j.MDC;

/**
 * Каркас сквозного контекста корреляции для структурных JSON-логов.
 * <p>
 * Значения кладутся в SLF4J {@link MDC} и автоматически попадают в каждую запись
 * структурного лога (см. {@code logging.structured.format.console} в application.yml).
 * <p>
 * Ключ {@link #CORRELATION_ID} — основной идентификатор запроса/сообщения,
 * проставляется {@link CorrelationIdFilter} на каждый входящий HTTP-запрос.
 * Остальные слоты (борт/рейс/инстанс последовательности/сообщение) — каркас
 * для движка/интеграции (наполняются в P1/P2), сейчас не используются массово.
 * <p>
 * Имя ключа {@link #CORRELATION_ID} ({@value #CORRELATION_ID}) согласовано с db-dev
 * для записи в колонку {@code audit_log.correlation_id}.
 */
public final class CorrelationContext {

    /** Основной correlationId запроса/сообщения. Согласовано с db-dev для audit_log.correlation_id. */
    public static final String CORRELATION_ID = "correlationId";

    /** Борт (tail number), например VP-BQR. */
    public static final String TAIL_NUMBER = "tailNumber";

    /** Идентификатор рейса (flight id), например SU1234. */
    public static final String FLIGHT_ID = "flightId";

    /** Идентификатор инстанса последовательности (execution instance). */
    public static final String INSTANCE_ID = "instanceId";

    /** Идентификатор обрабатываемого сообщения (ACARS downlink/uplink/ground). */
    public static final String MESSAGE_ID = "messageId";

    private CorrelationContext() {
    }

    public static void putCorrelationId(String correlationId) {
        put(CORRELATION_ID, correlationId);
    }

    public static void putTail(String tailNumber) {
        put(TAIL_NUMBER, tailNumber);
    }

    public static void putFlight(String flightId) {
        put(FLIGHT_ID, flightId);
    }

    public static void putInstance(String instanceId) {
        put(INSTANCE_ID, instanceId);
    }

    public static void putMessage(String messageId) {
        put(MESSAGE_ID, messageId);
    }

    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID);
    }

    /** Очищает все слоты контекста корреляции из MDC текущего потока. */
    public static void clear() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(TAIL_NUMBER);
        MDC.remove(FLIGHT_ID);
        MDC.remove(INSTANCE_ID);
        MDC.remove(MESSAGE_ID);
    }

    private static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }
}
