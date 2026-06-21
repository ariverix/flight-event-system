/**
 * P2-4 (часть 2 — логика): разбор позывного (callsign) и матчинг с {@code callsign_matching}
 * (P2-4 часть 1, схема — db-dev) для определения flight id (FI), используемого движком наравне
 * с tail number (AN) для привязки последовательности к рейсу (CLAUDE.md: «Привязка к борту:
 * tail number (AN) ИЛИ flight id (FI)+flight data»).
 *
 * <p>Внутренний пакет модуля {@code integration} (не named-interface) — используется как
 * {@link ru.protectinfotrans.eca.integration.callsign.CallsignMatchingService} изнутри
 * {@code integration.parser}/{@code integration.adapter.in} в момент нормализации входящего
 * сообщения, до передачи в {@code eventprocessor.port.in.MessageInputPort}.
 */
package ru.protectinfotrans.eca.integration.callsign;
