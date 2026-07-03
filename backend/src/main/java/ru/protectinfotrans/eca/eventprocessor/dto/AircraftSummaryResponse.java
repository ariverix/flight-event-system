package ru.protectinfotrans.eca.eventprocessor.dto;

import java.time.LocalDateTime;

/**
 * Фаза 5 (прогон апгрейда): сводка по борту для UI aircraft-bindings (закрытие TODO P7-3 —
 * привязка последовательности к бортам по tail number / AN).
 *
 * <p>В системе НЕТ отдельной таблицы-реестра бортов и НЕТ данных о типе ВС — борта существуют
 * неявно как различные {@code aircraft_id} (регистрационный номер / tail number) в журнале
 * сообщений. Эта сводка — проекция {@code GROUP BY aircraft_id} над таблицей {@code messages}:
 * для каждого борта отдаётся последний контакт и объём наблюдений. Именно tail number нужен UI
 * для привязки последовательности к борту.
 *
 * <p>Record используется одновременно как цель JPQL-конструктора (проекция запроса) и как тело
 * ответа REST — это DTO (не сущность {@code IncomingMessage}), инвариант DTO≠Entity соблюдён.
 * Типы счётчиков — {@code Long} (результат {@code COUNT(...)} в JPQL).
 *
 * @param aircraftId   регистрационный номер борта (tail number / AN)
 * @param lastSeen     время последнего принятого сообщения по этому борту
 * @param messageCount всего принятых сообщений по борту
 * @param flightCount  число различных рейсов (flight_number), наблюдавшихся у борта
 */
public record AircraftSummaryResponse(
        String aircraftId,
        LocalDateTime lastSeen,
        Long messageCount,
        Long flightCount
) {
}
