package ru.protectinfotrans.eca.conditions.domain;

/**
 * "Условие нельзя поднять дважды одним именем" — паритет с SITA Sequencer.
 *
 * <p><b>Почему ошибка, а не идемпотентный no-op:</b> raise condition с уже активным именем — это
 * сценарная ошибка сценария (либо автор ACTION-шага забыл сначала close, либо логическая ошибка
 * сценария дважды поднимает то же условие на разных путях исполнения), а НЕ повторная доставка
 * одной и той же команды (в отличие от {@code OutboundMessage} dedup по
 * {@code (executionInstanceId, stepOrderIndex)}, P2-3 — там цель идемпотентности — защита от
 * технического дубля при retry/resume ОДНОГО И ТОГО ЖЕ шага). Если бы повторный raise тихо
 * схлопывался в no-op, попытка ПОДНЯТЬ условие с ДРУГИМ уровнем алерта (например эскалация
 * LOW -> HIGH через повторный RAISE_CONDITION вместо явного CLOSE_CONDITION + RAISE_CONDITION)
 * молча игнорировалась бы — уровень алерта остался бы старым, и оператор не узнал бы об
 * эскалации. Явная ошибка ACTION-шага (см. {@code ActionStepRule} — перехватывается, превращается
 * в {@code StepResult.FAILURE}) сохраняет существующую семантику движка: автор сценария решает
 * через decision-граф (CONTINUE/GOTO/END/ABORT на false), что делать при попытке повторного raise.
 */
public class ConditionAlreadyRaisedException extends RuntimeException {

    public ConditionAlreadyRaisedException(String aircraftId, String flightNumber, String conditionName) {
        super("Condition '" + conditionName + "' is already raised for aircraft=" + aircraftId
                + ", flight=" + flightNumber + " — close it first before raising again");
    }
}
