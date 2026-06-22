package ru.protectinfotrans.eca.templates.application;

import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Движок подстановки переменных в тело шаблона — детерминированный рендеринг
 * (один и тот же вход {@code body + variables} всегда даёт один и тот же выход, без скрытого
 * состояния/времени/случайности — требование CLAUDE.md).
 *
 * <p><b>Синтаксис плейсхолдера — {@code {{variableName}}} (Mustache-подобный), а не
 * {@code ${variableName}}:</b>
 * <ul>
 *   <li>{@code ${...}} занят в Spring под property placeholders (application.yml/
 *       {@code @Value}) — использование того же синтаксиса в теле шаблона, хранимого в БД,
 *       создавало бы риск визуальной путаницы для оператора, заполняющего шаблон в UI, и
 *       потенциальный конфликт, если когда-либо шаблон будет проходить через Spring
 *       {@code PropertyPlaceholderHelper} на каком-то слое;</li>
 *   <li>{@code {{...}}} однозначно читается как "плейсхолдер шаблона сообщения" (тот же
 *       визуальный язык, что Mustache/Handlebars — широко знаком операторам, не требует
 *       обучения), не пересекается ни с одним другим синтаксисом, используемым в проекте
 *       (JSON-конфиги шагов/критериев используют обычные {@code {"key":"value"}} без двойных
 *       скобок).</li>
 * </ul>
 *
 * <p><b>Имя переменной:</b> {@code [A-Za-z_][A-Za-z0-9_.]*} — буквы/цифры/подчёркивание/точка
 * (точка зарезервирована для будущей вложенной адресации custom fields, P3-2, например
 * {@code {{customField.ETA_DELAY}}}), без пробелов внутри плейсхолдера.
 *
 * <p><b>Отсутствующая переменная — намеренно {@link MissingTemplateVariableException}, не тихая
 * замена на пустую строку/маркер:</b> SITA-паритетный шаблон used in ACTION send uplink/ground —
 * отправка борту/диспетчеру сообщения с "дырой" вместо значения (например,
 * {@code "ETA {{eta}}"} -> {@code "ETA "}) — тихий и трудно диагностируемый дефект на проде
 * (никто не узнает, что подстановка не сработала, пока не разберётся диспетчер на земле).
 * Бросаем исключение немедленно при рендеринге — вызывающая сторона (ACTION-шаг) получает явный
 * сбой (FAILURE шага), что соответствует общему принципу проекта "явный сбой лучше тихого дефекта"
 * (см. также {@code ActionStepRule.execute} catch-блок). Экранирование: значения переменных
 * подставляются как есть (стандартное строковое представление {@code toString()}), БЕЗ
 * экранирования спецсимволов плейсхолдера в значении — обоснование: это просто конкатенация
 * текста (uplink/ground — текстовый канал, не HTML/SQL), угрозы инъекции в смысле кода нет;
 * единственный риск — если значение переменной САМО содержит {@code {{...}}}, оно не будет
 * рекурсивно подставлено повторно (single-pass replace, см. {@link #render}), что является
 * желаемым поведением (предотвращает path для injection через данные из custom fields, P3-2,
 * где значение поля могло прийти из внешнего ACARS-сообщения).
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\}\\}");

    /**
     * Рендерит тело шаблона, подставляя значения переменных.
     *
     * <p>Single-pass: каждый плейсхолдер заменяется РОВНО ОДИН РАЗ, результат подстановки не
     * пересканируется на новые плейсхолдеры (защита от рекурсивной/повторной подстановки —
     * см. javadoc класса).
     *
     * @param body тело шаблона с плейсхолдерами {@code {{variableName}}}
     * @param variables значения переменных (точка подачи параметров ACTION-шага сейчас, P3-1;
     *                   и извлечённых custom fields рейса, P3-2 — тот же контракт, без изменений)
     * @return текст с подставленными значениями
     * @throws MissingTemplateVariableException если в {@code body} есть плейсхолдер, для
     *         которого нет значения в {@code variables}
     */
    public String render(String body, Map<String, Object> variables) {
        if (body == null) {
            return null;
        }
        Map<String, Object> safeVariables = variables == null ? Map.of() : variables;

        Matcher matcher = PLACEHOLDER.matcher(body);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            String variableName = matcher.group(1);
            if (!safeVariables.containsKey(variableName)) {
                throw new MissingTemplateVariableException(variableName);
            }
            Object value = safeVariables.get(variableName);

            result.append(body, lastEnd, matcher.start());
            result.append(value == null ? "" : value.toString());
            lastEnd = matcher.end();
        }
        result.append(body, lastEnd, body.length());

        return result.toString();
    }

    /** Список имён плейсхолдеров, встречающихся в теле шаблона (для UI/валидации параметров). */
    public java.util.Set<String> extractVariableNames(String body) {
        if (body == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
