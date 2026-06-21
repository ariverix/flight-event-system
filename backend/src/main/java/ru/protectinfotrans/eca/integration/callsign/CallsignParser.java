package ru.protectinfotrans.eca.integration.callsign;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор позывного (callsign) рейса на ICAO-код перевозчика + номер рейса (P2-4, часть 2).
 *
 * <p><b>Что такое "позывной" здесь и почему именно ICAO-код.</b> SITA AIRCOM Sequencer
 * callsign matching table (CLAUDE.md: «ICAO carrier code, start/end date, дни недели, dep/arr
 * airport, specificity») сопоставляет именно <b>ICAO-позывной</b> (3-буквенный код перевозчика,
 * передаваемый по радио/в ATC-плане полёта, напр. {@code "AFL1234"} = Аэрофлот рейс 1234) с
 * flight id (FI) — внутренним идентификатором рейса в системе (часто это IATA-обозначение,
 * напр. {@code "SU1234"}, или внутренний код авиакомпании). Эфирный позывной и итоговый FI —
 * РАЗНЫЕ строки одного и того же рейса, поэтому матчинг и нужен (паритет демо-данных V28:
 * {@code AFL1234 -> SU1234}).
 *
 * <p><b>ICAO vs IATA на входе.</b> Парсер принимает то, что реально приходит на вход
 * матчинга — строку позывного. Подавляющее большинство источников ACARS/ADS-B/радар отдают
 * ICAO-форму (3 буквы), это и есть основной случай. IATA-форма (2 символа: 2 буквы, либо
 * буква+цифра, напр. {@code "SU1234"}) на входе матчинга — вырожденный случай: если на входе
 * уже IATA-код, парсер всё равно успешно выделяет код перевозчика + номер (тем же алгоритмом —
 * длина буквенного префикса определяется автоматически), это позволяет переиспользовать один
 * парсер для обоих представлений без двух отдельных грамматик. Но {@link CallsignMatchingRule
 * правила в таблице задаются по ICAO-коду} (схема V28, {@code icao_carrier_code}) — матчинг
 * 2-буквенного IATA-префикса с ICAO-кодом правила в общем случае не сработает (это разные коды
 * у большинства перевозчиков, напр. IATA {@code SU} vs ICAO {@code AFL}); это осознанное
 * ограничение, не баг — реальный эфирный ACARS/ADS-B позывной практически всегда ICAO.
 *
 * <p><b>Грамматика:</b> {@code <буквенный код перевозчика 2-3 буквы><номер рейса 1-4 цифры
 * с опциональным буквенным суффиксом>}, например {@code "AFL1234"}, {@code "SU1234"},
 * {@code "BAW123A"}. Пробелы/дефисы между кодом и номером допускаются и игнорируются
 * ({@code "AFL 1234"} эквивалентно {@code "AFL1234"}) — оба написания встречаются в реальных
 * источниках (план полёта обычно слитно, голосовой позывной иногда передаётся с разделением).
 */
@Component
public class CallsignParser {

    /**
     * Группа 1 — буквенный код перевозчика (2-3 буквы), группа 2 — номер рейса (1-4 цифры +
     * опциональный буквенный суффикс). Код перевозчика жадно матчится по верхней границе (3
     * буквы), затем по нижней (2 буквы) — Java regex backtracking сам подбирает разбиение так,
     * чтобы после букв нашлась цифра (нельзя истолковать 4 буквы как код перевозчика — паттерн
     * ограничен {@code {2,3}}).
     */
    private static final Pattern CALLSIGN_PATTERN =
            Pattern.compile("^([A-Z]{2,3})[\\s-]?(\\d{1,4}[A-Z]?)$");

    /**
     * Разобрать позывной на ICAO/IATA-код перевозчика + номер рейса.
     *
     * @param callsign строка позывного, как встретилась в сообщении/плане полёта (регистр не
     *                 важен — нормализуется к верхнему)
     * @return разобранный позывной, либо {@link Optional#empty()} если строка не соответствует
     *         грамматике позывного (не похожа на "код перевозчика + номер рейса") — это НЕ
     *         ошибка, вызывающая сторона (callsign matching) просто не находит FI и оставляет
     *         исходную привязку как есть (P2-4: «нет совпадения -> нет FI»)
     */
    public Optional<ParsedCallsign> parse(String callsign) {
        if (callsign == null || callsign.isBlank()) {
            return Optional.empty();
        }

        String normalized = callsign.strip().toUpperCase();
        Matcher matcher = CALLSIGN_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String carrierCode = matcher.group(1);
        String flightNumber = matcher.group(2);
        return Optional.of(new ParsedCallsign(carrierCode, flightNumber));
    }
}
