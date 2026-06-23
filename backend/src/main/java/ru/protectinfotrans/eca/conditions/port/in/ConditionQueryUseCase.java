package ru.protectinfotrans.eca.conditions.port.in;

import ru.protectinfotrans.eca.conditions.domain.RaisedCondition;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.util.List;
import java.util.Map;

/**
 * Входной порт чтения активных условий рейса — главный публичный контракт для {@code execution}
 * (критерий {@code CONDITION_ACTIVE}, см. {@code CriterionEvaluator}/{@code ExecutionService
 * #buildContext}, тот же приём, что {@code CustomFieldQueryUseCase}, P3-2).
 */
public interface ConditionQueryUseCase {

    /**
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @param conditionName название условия
     * @return true, если условие активно (поднято и не закрыто) для ЭТОГО рейса
     */
    boolean isConditionActive(String aircraftId, String flightNumber, String conditionName);

    /**
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @return карта {@code conditionName -> alertLevel} всех активных условий рейса; пустая карта,
     *         если ни одного условия не поднято (или рейс/борт неизвестен)
     */
    Map<String, AlertLevel> getActiveConditions(String aircraftId, String flightNumber);

    /**
     * Все активные условия по ВСЕМ бортам/рейсам — операторский обзор (RBAC-эндпоинт
     * {@code GET /api/v1/conditions}, см. {@code ConditionController}).
     */
    List<RaisedCondition> listAllActive();
}
