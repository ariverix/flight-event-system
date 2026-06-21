package ru.protectinfotrans.eca.integration.callsign;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.protectinfotrans.eca.integration.domain.CallsignMatchingRule;
import ru.protectinfotrans.eca.integration.port.out.CallsignMatchingRepositoryPort;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * P2-4 (часть 2 — логика): определение flight id (FI) по позывному (callsign), паритет SITA
 * AIRCOM Sequencer "callsign matching table" (CLAUDE.md: «ICAO carrier code, start/end date,
 * дни недели, dep/arr airport, specificity»).
 *
 * <p><b>Алгоритм</b> (TЗ db-dev, P2-4 часть 1 — миграция V28 уже даёт схему + первый фильтр):
 * <ol>
 *   <li>{@link CallsignParser} разбирает входной позывной на ICAO-код перевозчика + номер рейса;
 *       не похоже на позывной -> сразу {@link Optional#empty()} (часть 1 алгоритма ниже не
 *       вызывается совсем — нет кода перевозчика, нет смысла идти в БД);</li>
 *   <li>{@link CallsignMatchingRepositoryPort#findCandidates} — первый, самый избирательный
 *       фильтр уже в SQL (V28: индексы по перевозчику + периоду): активные правила этого
 *       перевозчика, действующие на дату полёта/сообщения (период {@code valid_from..valid_to},
 *       оба края nullable);</li>
 *   <li>дофильтровка В КОДЕ среди кандидатов (специфика SITA-таблицы — номер рейса/день
 *       недели/аэропорты комбинируются по {@code NULL = "любой"}, что неудобно/избыточно
 *       выражать одним SQL-условием при не более чем единицах-десятках правил на перевозчика):
 *       <ul>
 *         <li>номер рейса — точное совпадение ИЛИ {@code flightNumber == null} (общее правило);</li>
 *         <li>день недели — бит на позиции {@code dayOfWeek.getValue()} (1=Пн..7=Вс, ISO,
 *             та же нумерация что и в V28/{@code days_of_week}) равен {@code '1'} ИЛИ
 *             {@code daysOfWeek == null} (любой день);</li>
 *         <li>аэропорт вылета/прилёта — точное совпадение (регистронезависимо) ИЛИ
 *             {@code null} в правиле (любой аэропорт);</li>
 *       </ul>
 *   </li>
 *   <li>среди прошедших все три проверки — {@code max(specificity)}; при равенстве —
 *       tie-breaker {@code createdAt DESC} (более новое правило выигрывает у более старого с
 *       тем же приоритетом — типичная семантика "последнее заданное правило важнее" для
 *       справочников без отдельного порядкового номера);</li>
 *   <li>{@link CallsignMatchingRule#getFlightId()} победителя — результат. Нет ни одного
 *       прошедшего кандидата -> {@link Optional#empty()}, FI НЕ выдумывается (ТЗ: «нет
 *       совпадения -> нет FI», привязка остаётся на tail number/AN как раньше).</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallsignMatchingService {

    private final CallsignParser callsignParser;
    private final CallsignMatchingRepositoryPort callsignMatchingRepository;

    /**
     * Определить flight id (FI) по позывному, дате полёта/сообщения и (опционально)
     * аэропортам вылета/прилёта.
     *
     * @param callsign         позывной как он встретился в сообщении (см. {@link CallsignParser})
     * @param onDate           дата, на которую ищется действующее правило (период/день недели)
     * @param departureAirport аэропорт вылета, известный на момент матчинга, либо {@code null}
     *                         если неизвестен (правила с конкретным аэропортом тогда не матчатся,
     *                         только правила с {@code departureAirport == null})
     * @param arrivalAirport   аэропорт прилёта, аналогично
     * @return FI лучшего совпавшего правила, либо {@link Optional#empty()} если позывной не
     *         распарсен или ни одно правило не подошло
     */
    public Optional<String> resolveFlightId(String callsign, LocalDate onDate,
                                             String departureAirport, String arrivalAirport) {
        Optional<ParsedCallsign> parsed = callsignParser.parse(callsign);
        if (parsed.isEmpty()) {
            log.debug("Callsign matching: '{}' не похож на позывной (код перевозчика+номер) — пропускаем", callsign);
            return Optional.empty();
        }

        ParsedCallsign callsignParts = parsed.get();
        List<CallsignMatchingRule> candidates =
                callsignMatchingRepository.findCandidates(callsignParts.icaoCarrierCode(), onDate);

        DayOfWeek dayOfWeek = onDate.getDayOfWeek();

        Optional<CallsignMatchingRule> winner = candidates.stream()
                .filter(rule -> matchesFlightNumber(rule, callsignParts.flightNumber()))
                .filter(rule -> matchesDayOfWeek(rule, dayOfWeek))
                .filter(rule -> matchesAirport(rule.getDepartureAirport(), departureAirport))
                .filter(rule -> matchesAirport(rule.getArrivalAirport(), arrivalAirport))
                .max(Comparator.comparing(CallsignMatchingRule::getSpecificity)
                        .thenComparing(CallsignMatchingRule::getCreatedAt));

        if (winner.isEmpty()) {
            log.debug("Callsign matching: позывной '{}' (carrier={}, number={}) на {} — нет совпавшего правила",
                    callsign, callsignParts.icaoCarrierCode(), callsignParts.flightNumber(), onDate);
            return Optional.empty();
        }

        CallsignMatchingRule rule = winner.get();
        log.info("Callsign matching: позывной '{}' -> FI '{}' (правило id={}, specificity={})",
                callsign, rule.getFlightId(), rule.getId(), rule.getSpecificity());
        return Optional.of(rule.getFlightId());
    }

    private boolean matchesFlightNumber(CallsignMatchingRule rule, String flightNumber) {
        return rule.getFlightNumber() == null || rule.getFlightNumber().equalsIgnoreCase(flightNumber);
    }

    private boolean matchesDayOfWeek(CallsignMatchingRule rule, DayOfWeek dayOfWeek) {
        String mask = rule.getDaysOfWeek();
        if (mask == null) {
            return true;
        }
        int position = dayOfWeek.getValue() - 1; // ISO: Пн=1..Вс=7 -> индекс 0..6
        return position >= 0 && position < mask.length() && mask.charAt(position) == '1';
    }

    private boolean matchesAirport(String ruleAirport, String actualAirport) {
        return ruleAirport == null || ruleAirport.equalsIgnoreCase(actualAirport);
    }
}
