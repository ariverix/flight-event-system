package ru.protectinfotrans.eca.conditions.port.out;

import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Выходной порт персистентности {@code raised_conditions} — реализуется JPA-адаптером. */
public interface ConditionRepositoryPort {

    /** Активная (не закрытая) строка по имени условия для рейса, если она существует. */
    Optional<RaisedCondition> findActiveByAircraftIdAndFlightNumberAndConditionName(
            String aircraftId, String flightNumber, String conditionName);

    /** Все активные (не закрытые) условия рейса — горячий путь {@code CONDITION_ACTIVE}. */
    List<RaisedCondition> findActiveByAircraftIdAndFlightNumber(String aircraftId, String flightNumber);

    RaisedCondition save(RaisedCondition condition);

    /**
     * Закрывает (ставит {@code closedAt}) все ещё активные условия рейса — паритет с SITA
     * "активные условия закрываются автоматически при завершении рейса".
     *
     * @return количество закрытых строк (0 — рейс не имел активных условий, идемпотентно)
     */
    int closeAllActiveForFlight(String aircraftId, String flightNumber, LocalDateTime closedAt);

    /** Просмотр всех активных условий (по всем бортам/рейсам) для операторского UI (RBAC-эндпоинт). */
    List<RaisedCondition> findAllActive();
}
