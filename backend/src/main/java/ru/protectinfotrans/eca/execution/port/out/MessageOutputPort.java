package ru.protectinfotrans.eca.execution.port.out;

import java.util.List;
import java.util.Map;

/**
 * Выходной порт для отправки исходящих сообщений и управления пользовательскими условиями.
 * Реализует UC-07.
 *
 * Гексагональная архитектура: это Driven Port (выходной порт) — доменная логика execution
 * модуля вызывает этот порт, а адаптеры в integration модуле реализуют его.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6 (MessageOutputPort — выходной порт)
 */
public interface MessageOutputPort {

    /**
     * UC-07: Отправить сообщение на борт (uplink).
     *
     * @param aircraftId идентификатор ВС
     * @param templateName имя шаблона сообщения
     * @param params параметры для заполнения шаблона
     * @return true если отправка успешна
     */
    boolean sendUplink(String aircraftId, String templateName, Map<String, Object> params);

    /**
     * UC-07: Отправить наземное сообщение (ground).
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