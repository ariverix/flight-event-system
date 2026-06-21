package ru.protectinfotrans.eca.integration.adapter.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ru.protectinfotrans.eca.integration.parser.RawMessageFormat;

import java.time.LocalDate;

/**
 * DTO для приёма СЫРОГО (нераспарсенного) сообщения в одном из промышленных форматов
 * «борт-земля» (P2-2): ARINC 618/620, Type B, AFTN.
 *
 * <p>Формат явно указывается вызывающей стороной — см.
 * {@link RawMessageFormat} (обоснование отказа от авто-детекта).
 * Сообщение нормализуется {@code RawMessageParserService} (этот же модуль {@code integration}) в
 * структуру борт/рейс/тип/payload/externalMessageId и передаётся в
 * {@code eventprocessor.port.in.MessageInputPort#receiveMessage} — идемпотентность/persist/публикация
 * события (P2-1) применяются без изменений, включая случай, когда {@code externalMessageId}
 * извлечён из самого сообщения (ARINC message reference / AFTN serial number).
 *
 * <p><b>departureAirport/arrivalAirport (P2-4, часть 2):</b> опциональные поля плана полёта —
 * сами текстовые форматы ARINC 618/620/Type B/AFTN, разбираемые в P2-2, не несут аэропортов
 * вылета/прилёта как структурное поле тела телеграммы, но внешний ACARS/AFTN-гейтвей (источник
 * запроса) обычно знает план полёта рейса (то же сообщение в реальной наземной системе сопоставлено
 * с конкретным flight plan) — присылает их рядом с сырым текстом, не внутри него. Используются
 * ИСКЛЮЧИТЕЛЬНО для дофильтровки кандидатов {@code callsign_matching} по
 * {@code departure_airport}/{@code arrival_airport} (правило, не ограниченное по аэропорту,
 * матчится независимо от того, переданы эти поля или нет — {@code NULL} в правиле = "любой").
 * {@code null} — аэропорты неизвестны вызывающей стороне, правила с конкретным аэропортом тогда
 * не матчатся (нет данных для сравнения), это НЕ ошибка запроса.
 *
 * <p><b>flightDate (P2-4, часть 2):</b> дата полёта/сообщения, на которую ищется действующее
 * правило (период {@code valid_from..valid_to} + день недели {@code days_of_week}) — паритет
 * SITA-таблицы матчинга, которая сопоставляет правило именно дате РЕЙСА, не дате обработки
 * телеграммы наземной системой (телеграмма может быть обработана с задержкой/повторно).
 * {@code null} — используется дата получения запроса ({@link java.time.LocalDate#now()}),
 * приемлемо для подавляющего большинства телеграмм (обрабатываются почти сразу после подачи).
 */
public record RawIncomingMessageRequest(
        @NotNull RawMessageFormat format,
        @NotBlank String rawMessage,
        String departureAirport,
        String arrivalAirport,
        LocalDate flightDate
) {
    /**
     * Удобный конструктор для вызывающих, которым аэропорты/дата рейса неизвестны/не нужны
     * (большинство существующих и будущих вызовов до P2-4 — обратная совместимость без правки
     * существующих клиентов/тестов).
     */
    public RawIncomingMessageRequest(RawMessageFormat format, String rawMessage) {
        this(format, rawMessage, null, null, null);
    }

    /**
     * Удобный конструктор для вызывающих, которым нужны аэропорты, но не явная дата рейса
     * (используется дата получения запроса).
     */
    public RawIncomingMessageRequest(RawMessageFormat format, String rawMessage,
                                      String departureAirport, String arrivalAirport) {
        this(format, rawMessage, departureAirport, arrivalAirport, null);
    }
}
