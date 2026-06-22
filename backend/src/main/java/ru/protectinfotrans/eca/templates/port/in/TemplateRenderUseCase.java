package ru.protectinfotrans.eca.templates.port.in;

import java.util.Map;
import java.util.Optional;

/**
 * Входной порт рендеринга шаблона по имени — ГЛАВНЫЙ публичный контракт модуля {@code templates}
 * для остальной системы: {@code execution} (ACTION send uplink/ground рендерит payload перед
 * постановкой в durable-очередь) и {@code integration} (durable outbound поллер рендерит текст
 * непосредственно перед фактической отправкой во внешний канал).
 *
 * <p><b>Точка интеграции для P3-2 (custom fields):</b> {@code variables} — единая карта значений,
 * подаваемая в {@link ru.protectinfotrans.eca.templates.application.TemplateRenderer#render}.
 * Сейчас (P3-1) вызывающая сторона передаёт сюда {@code params} ACTION-конфига как есть. В P3-2
 * движок custom fields будет ОБЪЕДИНЯТЬ {@code params} ACTION-шага со значениями полей,
 * извлечённых из входящих сообщений текущего рейса (per-flight контекст), и передавать сюда
 * единую объединённую карту — сигнатура этого порта не меняется, P3-2 встраивается на стороне
 * вызывающего (execution/integration), не здесь.
 */
public interface TemplateRenderUseCase {

    /**
     * Рендерит активный шаблон с указанным именем.
     *
     * @param templateName имя шаблона (уникальный идентификатор, {@code Template#name})
     * @param variables значения переменных для подстановки в плейсхолдеры {@code {{var}}}
     * @return готовый текст для отправки в канал
     * @throws java.util.NoSuchElementException если шаблон с таким именем не найден
     * @throws MissingTemplateVariableException
     *         если в теле шаблона есть плейсхолдер без значения в {@code variables}
     */
    String render(String templateName, Map<String, Object> variables);

    /**
     * То же самое, но без исключения при отсутствии шаблона/недоступности справочника — для
     * путей, где ЭТИ два случая не должны ронять вызывающую транзакцию (используется выборочно,
     * основной путь — {@link #render}, бросающий исключение, чтобы не маскировать ошибку
     * конфигурации).
     *
     * <p><b>Важно — НЕ глушит {@link MissingTemplateVariableException}:</b>
     * если шаблон с именем {@code templateName} НАЙДЕН, но в {@code variables} не хватает
     * значения для одного из его плейсхолдеров — это пробрасывается наружу как есть, а НЕ
     * сворачивается в {@code Optional.empty()}. Причина: {@code Optional.empty()} — сигнал
     * "шаблон не разрешился, можно безопасно подставить {@code templateName} как есть"
     * (легитимный fallback для обратной совместимости со старой моделью без реестра шаблонов).
     * Найденный, но неполно параметризованный шаблон — это ДРУГОЙ по природе случай (ошибка
     * конфигурации ACTION-шага), и подмена его тем же fallback-поведением означала бы тихую
     * отправку имени шаблона в канал под видом успешно отрендеренного сообщения. Вызывающий
     * обязан различать: пойманное исключение этого типа здесь → трактовать как сбой доставки
     * (не markSent с подменённым мусорным текстом).
     *
     * @throws MissingTemplateVariableException
     *         если шаблон найден, но для одного из его плейсхолдеров нет значения в
     *         {@code variables}
     */
    Optional<String> tryRender(String templateName, Map<String, Object> variables);
}
