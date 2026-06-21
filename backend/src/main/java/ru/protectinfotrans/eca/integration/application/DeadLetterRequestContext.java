package ru.protectinfotrans.eca.integration.application;

/**
 * P2-6: контекст исходного raw-запроса, нужный для полноценного ручного reprocess DLQ-записи —
 * без него reprocess потерял бы departureAirport/arrivalAirport/flightDate (P2-4, часть 2),
 * влияющие на callsign matching, и повёл бы себя иначе, чем оригинальный приём.
 * Сериализуется в {@code DeadLetterMessage#requestContext} как JSON.
 */
public record DeadLetterRequestContext(
        String departureAirport,
        String arrivalAirport,
        java.time.LocalDate flightDate
) {
}
