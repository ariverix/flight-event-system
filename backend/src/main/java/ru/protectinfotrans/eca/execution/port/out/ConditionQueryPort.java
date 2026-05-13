package ru.protectinfotrans.eca.execution.port.out;

import java.util.Set;

/**
 * Выходной порт для запроса активных пользовательских условий.
 * Используется CriterionEvaluator для CONDITION_ACTIVE критерия.
 *
 * Гексагональная архитектура: это Driven Port (выходной порт) — доменная логика execution
 * модуля вызывает этот порт для проверки активных условий, а адаптеры в integration модуле
 * реализуют его.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6
 */
public interface ConditionQueryPort {

    /**
     * Проверить, активно ли пользовательское условие для ВС.
     *
     * @param aircraftId идентификатор ВС
     * @param conditionName название условия
     * @return true если условие активно
     */
    boolean isConditionActive(String aircraftId, String conditionName);

    /**
     * Получить все активные условия для ВС.
     *
     * @param aircraftId идентификатор ВС
     * @return set активных условий
     */
    Set<String> getActiveConditions(String aircraftId);
}
