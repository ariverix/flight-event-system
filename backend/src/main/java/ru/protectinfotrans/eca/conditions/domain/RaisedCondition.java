package ru.protectinfotrans.eca.conditions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.time.LocalDateTime;

/**
 * Именованное custom condition в контексте КОНКРЕТНОГО РЕЙСА (по борту + номеру рейса) — паритет
 * с SITA Sequencer: ACTION raise/close condition + независимый уровень алерта (No/Low/Medium/
 * High/Critical).
 *
 * <p><b>Per-flight, не per-aircraft (см. {@code CustomFieldValue} javadoc — тот же принцип,
 * P3-2):</b> ключ активного условия — {@code (aircraftId, flightNumber, conditionName)}, а НЕ
 * только {@code aircraftId}. На одном борту последовательно выполняются разные рейсы — условие,
 * поднятое для рейса SU1234, не должно "утечь" в следующий рейс SU1235 того же борта VP-BQR после
 * завершения первого. Старая in-memory реализация ({@code IntegrationService}, до этой задачи)
 * была keyed только по {@code aircraftId} — этот класс фиксирует исправленную, per-flight модель.
 *
 * <p><b>Условие и уровень алерта — РАЗНЫЕ сущности (паритет SITA, явный инвариант CLAUDE.md):</b>
 * {@link #conditionName} — идентичность условия (raised/closed, проверка "нельзя поднять дважды
 * одним именем" работает по нему); {@link #alertLevel} — атрибут серьёзности ТЕКУЩЕГО подъёма
 * условия, ортогональный самому факту "поднято/не поднято". Можно поднять условие с уровнем
 * {@code NO} (алертинг отсутствует, но условие физически активно — критерий
 * {@code CONDITION_ACTIVE} видит его как true) — это не противоречие, это и есть "условие и алерт
 * независимы": уровень не определяет, активно условие или нет, он только описывает, НАСКОЛЬКО
 * серьёзно оно отражается оператору.
 *
 * <p><b>"Нельзя поднять дважды одним именем" — реализовано как уникальность АКТИВНОЙ строки:</b>
 * {@link #closedAt} {@code null} = условие активно; частичный уникальный индекс
 * {@code (aircraft_id, flight_number, condition_name) WHERE closed_at IS NULL} (V33) гарантирует
 * на уровне БД, что для одного рейса не может существовать ДВЕ одновременно активные строки с
 * одним именем — повторный raise того же ещё не закрытого условия (см.
 * {@code ConditionManagementService#raiseCondition}) отклоняется ДО попытки INSERT (явная проверка
 * в сервисе) — ошибка domain-уровня {@code ConditionAlreadyRaisedException}, а не
 * {@code DataIntegrityViolationException} с уровня БД (тот же индекс — defense in depth на случай
 * конкурентной гонки двух одновременных raise одного и того же имени).
 *
 * <p><b>{@link #closedAt} — мягкое закрытие, история не теряется (тот же принцип, что
 * {@code CustomFieldValue#closedAt}):</b> close condition (вручную ИЛИ автоматически при
 * завершении рейса, см. {@code FlightConditionLifecycleUseCase}) ставит {@code closedAt}, строка
 * остаётся в БД для аудита/трассировки ("какие условия поднимались на этом рейсе и когда") —
 * физического DELETE нет. После повторного raise того же имени на ТОМ ЖЕ рейсе ПОСЛЕ закрытия
 * создаётся НОВАЯ строка (а не reopen старой, в отличие от {@code CustomFieldValue#upsert}) —
 * сохраняет полную историю отдельных подъёмов условия с их собственными {@code alertLevel}/
 * {@code raisedAt}/{@code closedAt} (close condition не имеет понятия "перезаписать", только
 * "закрыть текущее активное").
 */
@Entity
@Table(name = "raised_conditions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RaisedCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aircraft_id", nullable = false, length = 50)
    private String aircraftId;

    @Column(name = "flight_number", nullable = false, length = 50)
    private String flightNumber;

    @Column(name = "condition_name", nullable = false, length = 255)
    private String conditionName;

    /** Уровень алерта ТЕКУЩЕГО подъёма условия — независим от факта raised/closed, см. javadoc класса. */
    @Enumerated(EnumType.STRING)
    @Column(name = "alert_level", nullable = false, length = 20)
    private AlertLevel alertLevel;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    /** Не-null = условие закрыто (вручную close condition ИЛИ авто-закрытие на IN/SUMMARY). */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    protected void onCreate() {
        if (raisedAt == null) {
            raisedAt = LocalDateTime.now();
        }
    }
}
