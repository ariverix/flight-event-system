package ru.protectinfotrans.eca.integration.callsign;

/**
 * Результат разбора позывного (callsign) на ICAO-код перевозчика + номер рейса (P2-4, часть 2).
 *
 * @param icaoCarrierCode ICAO-код перевозчика (3 буквы, напр. {@code "AFL"}) — ключ матчинга
 *                        в {@code callsign_matching} ({@link ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule#getIcaoCarrierCode()})
 * @param flightNumber    номер рейса из позывного (напр. {@code "1234"}), без кода перевозчика
 */
public record ParsedCallsign(String icaoCarrierCode, String flightNumber) {
}
