package ru.protectinfotrans.eca.integration.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * P2-4 (часть 1 — схема): правило соответствия позывного (callsign) flight id (FI), паритет
 * SITA AIRCOM Sequencer "callsign matching table" (CLAUDE.md: «callsign matching table (ICAO
 * code, даты, дни недели, dep/arr airport)»).
 *
 * <p><b>Назначение и границы:</b> позывной борта в эфире разбирается (часть 2, integration-dev)
 * на {@link #icaoCarrierCode} ("AFL") + {@link #flightNumber} ("1234"); по этой паре, а также
 * дате полёта, дню недели и аэропортам вылета/прилёта подбирается это правило, и его
 * {@link #flightId} используется движком как FI наравне с tail number (AN) для привязки
 * последовательности к рейсу (CLAUDE.md: «Привязка к борту: tail number (AN) ИЛИ flight id
 * (FI)+flight data»). Сам алгоритм разбора позывного и выбор лучшего кандидата среди
 * совпавших правил — часть 2 (integration-dev), эта сущность только хранит правило.
 *
 * <p><b>Размещение в модуле {@code integration}, а не отдельным модулем</b> — обоснование (по
 * аналогии с {@link OutboundMessage} P2-3): callsign matching — часть зоны "разбор позывных",
 * закреплённой за {@code integration-dev}/модулем integration в TEAM.md ("ACARS/AFTN/Type B,
 * ARINC-парсинг, позывные+matching table, позиции, DLQ/ретраи"). Читается и пишется там же,
 * где разбираются входящие позывные (парсеры {@code integration.parser}) — заводить отдельный
 * Modulith-модуль только под одну справочную таблицу без собственного домена/событий избыточно
 * (минимум абстракций "на будущее", CLAUDE.md "Стиль кода"). Если в будущем матчинг обзаведётся
 * собственным жизненным циклом/событиями уровня домена — выделение в отдельный модуль
 * рассматривает {@code architect} отдельным ADR.
 *
 * <p>Без FK на другие таблицы — справочное правило не зависит от execution/sequence (тот же
 * принцип "без FK", что у {@link OutboundMessage}/{@code TrackingEventLog}).
 */
@Entity
@Table(name = "callsign_matching")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallsignMatchingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ICAO-код перевозчика, разобранный из позывного (напр. "AFL", "SVR", "BAW"). */
    @Column(name = "icao_carrier_code", nullable = false, length = 10)
    private String icaoCarrierCode;

    /**
     * Номер рейса из позывного (напр. "1234"). {@code NULL} = правило действует для любого
     * номера рейса этого перевозчика (общее правило, низкая {@link #specificity}).
     */
    @Column(name = "flight_number", length = 20)
    private String flightNumber;

    /** Результат сопоставления — flight id (FI), используемый движком наравне с tail number. */
    @Column(name = "flight_id", nullable = false, length = 50)
    private String flightId;

    /** Начало периода действия правила включительно. {@code NULL} = без ограничения снизу. */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /** Конец периода действия правила включительно. {@code NULL} = бессрочно (без ограничения сверху). */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /**
     * Дни недели действия — битовая маска длиной 7 (позиция 1..7 = понедельник..воскресенье,
     * '1' — действует, '0' — не действует), напр. {@code "1111100"} = будни. {@code NULL} =
     * действует во все дни недели.
     */
    @Column(name = "days_of_week", length = 7)
    private String daysOfWeek;

    /** Аэропорт вылета (ICAO/IATA). {@code NULL} = любой аэропорт вылета. */
    @Column(name = "departure_airport", length = 10)
    private String departureAirport;

    /** Аэропорт прилёта (ICAO/IATA). {@code NULL} = любой аэропорт прилёта. */
    @Column(name = "arrival_airport", length = 10)
    private String arrivalAirport;

    /**
     * Приоритет правила при множественном совпадении кандидатов — чем выше значение, тем
     * более конкретно правило и тем больший приоритет оно имеет при выборе среди совпавших
     * правил (часть 2: сортировка по {@code specificity DESC}, первый кандидат — победитель).
     * Не вычисляется автоматически — задаётся явно при создании правила.
     */
    @Column(name = "specificity", nullable = false)
    private Integer specificity;

    /** Активность правила — неактивные правила сохраняются (история), но не участвуют в матчинге. */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (specificity == null) {
            specificity = 0;
        }
        if (active == null) {
            active = true;
        }
    }
}
