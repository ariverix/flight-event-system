package ru.protectinfotrans.eca.conditions.port.in;

import ru.protectinfotrans.eca.conditions.domain.ConditionAlreadyRaisedException;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

/**
 * Входной порт raise/close custom condition — паритет с SITA Sequencer: ACTION-шаг RAISE_CONDITION/
 * CLOSE_CONDITION (см. {@code ActionStepRule}, единственный потребитель в {@code execution}).
 */
public interface ConditionManagementUseCase {

    /**
     * Поднимает именованное условие для рейса (борт + номер рейса) с заданным уровнем алерта.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса (per-flight ключ, см. {@code RaisedCondition} javadoc)
     * @param conditionName название условия
     * @param alertLevel уровень алерта ТЕКУЩЕГО подъёма — независимый атрибут, не влияет на сам
     *                    факт активности условия (можно поднять с {@link AlertLevel#NO})
     * @throws ConditionAlreadyRaisedException если условие с этим именем уже активно для этого
     *         рейса — паритет SITA "нельзя поднять дважды одним именем" (см. её javadoc, почему
     *         это ошибка, а не идемпотентный no-op)
     */
    void raiseCondition(String aircraftId, String flightNumber, String conditionName, AlertLevel alertLevel);

    /**
     * Закрывает именованное условие для рейса. Идемпотентно: закрытие уже закрытого/никогда не
     * поднятого условия — no-op (НЕ ошибка), в отличие от raise — close не нужно защищать от
     * "случайного дубля", повторный close корректно отражает желаемое конечное состояние
     * "условие закрыто" независимо от того, сколько раз он был вызван.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @param conditionName название условия
     */
    void closeCondition(String aircraftId, String flightNumber, String conditionName);
}
