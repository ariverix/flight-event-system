package ru.protectinfotrans.eca.customfields.port.in;

import java.util.Map;

/**
 * Входной порт чтения АКТИВНЫХ (не закрытых, см. {@code CustomFieldValue#closedAt}) custom field
 * значений рейса — главный публичный контракт для {@code execution} (подстановка в критерии через
 * {@code ExecutionContext.additionalData}, объединение переменных перед рендерингом шаблона
 * ACTION-шага) и {@code integration} (объединение переменных непосредственно перед фактической
 * доставкой outbound-сообщения, тот же принцип, что рендеринг шаблона в
 * {@code OutboundMessageDeliveryScheduler}, P3-1).
 */
public interface CustomFieldQueryUseCase {

    /**
     * Возвращает ВСЕ открытые (не закрытые завершением рейса) значения custom fields для данного
     * рейса как карту {@code fieldName -> value}, готовую либо к прямому объединению с {@code
     * Map<String, Object> variables} перед {@code TemplateRenderUseCase#render/tryRender} (ключи
     * подставляются ТОЧЕЧНОЙ адресацией {@code {{customField.<fieldName>}}} вызывающей стороной —
     * см. ниже), либо к прямому помещению в {@code ExecutionContext.additionalData} под ключом
     * {@code "customFields"} (тот же приём, что {@code activeConditions}/{@code offTime}).
     *
     * <p><b>Префикс {@code "customField."} добавляется здесь, а не вызывающей стороной:</b> порт
     * возвращает карту С УЖЕ ПРЕФИКСИРОВАННЫМИ ключами ({@code "customField." + fieldName}) —
     * единый формат для ОБОИХ потребителей (шаблоны и критерии), чтобы не дублировать логику
     * префиксации в {@code execution} И в {@code integration} по отдельности.
     *
     * @param aircraftId идентификатор ВС
     * @param flightNumber номер рейса
     * @return неизменяемая карта {@code "customField.<fieldName>" -> значение}; пустая карта, если
     *         для рейса нет открытых значений (в том числе если рейс уже закрыт или ничего не
     *         извлекалось)
     */
    Map<String, String> getActiveValues(String aircraftId, String flightNumber);
}
