package ru.protectinfotrans.eca.execution.port.out;

import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

import java.util.List;
import java.util.Map;

/**
 * Выходной порт для отправки исходящих сообщений и управления пользовательскими условиями.
 *
 * модуля вызывает этот порт, а адаптеры в integration модуле реализуют его.
 *
 */
public interface MessageOutputPort {

    /**
     * Отправить сообщение на борт (uplink).
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @return true если отправка успешна
     */
    boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params);

    /**
     * Отправить сообщение на борт (uplink) с явным указанием происхождения шаблона —
     * паритет с SITA Sequencer: computer-generated | from external user.
     * Дефолтная реализация делегирует в 3-аргументный метод (COMPUTER_GENERATED) —
     * обратная совместимость для адаптеров, ещё не различающих происхождение шаблона.
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @param origin происхождение шаблона (computer-generated | external-user)
     * @return true если отправка успешна
     */
    default boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params, UplinkOrigin origin) {
        return sendUplink(aircraftId, templateName, params);
    }

    /**
     * Отправить наземное сообщение (ground).
     *
     * @param recipients список получателей
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @return true если отправка успешна
     */
    boolean sendGround(List<String> recipients, String templateName, Map<String, Object> params);

    /**
     * Поднять пользовательское условие (алерт) для ВС.
     *
     * @param aircraftId идентификатор ВС
     * @param conditionName название условия
     * @param alertLevel уровень алерта (INFO, WARNING, ERROR)
     * @return true если операция успешна
     */
    boolean raiseCondition(String aircraftId, String conditionName, String alertLevel);

    /**
     * Снять пользовательское условие для ВС.
     *
     * @param aircraftId идентификатор ВС
     * @param conditionName название условия
     * @return true если операция успешна
     */
    boolean closeCondition(String aircraftId, String conditionName);
}