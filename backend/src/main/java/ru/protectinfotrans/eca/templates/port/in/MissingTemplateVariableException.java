package ru.protectinfotrans.eca.templates.port.in;

/**
 * Брошено {@link ru.protectinfotrans.eca.templates.application.TemplateRenderer#render}, когда
 * тело шаблона содержит плейсхолдер {@code {{variableName}}}, для которого не передано значение.
 * См. javadoc {@code TemplateRenderer} — почему это исключение, а не тихая замена на пустую
 * строку/маркер.
 *
 * <p>Живёт в {@code templates.port.in} (а не в {@code templates.application}, где была
 * исторически), потому что это ЧАСТЬ ПУБЛИЧНОГО КОНТРАКТА {@link TemplateRenderUseCase} —
 * объявлена в его {@code @throws} ({@link TemplateRenderUseCase#render},
 * {@link TemplateRenderUseCase#tryRender}) и пробрасывается наружу другим модулям (execution,
 * integration — см. {@code OutboundMessageDeliveryScheduler#renderTemplate}), которые обязаны
 * различать её от "шаблон не найден"/инфраструктурного сбоя лукапа. {@code ApplicationModules.verify()}
 * (Spring Modulith) не позволяет другим модулям ссылаться на типы из {@code templates.application}
 * (внутренний, не-exposed пакет) — этот класс должен жить в {@code port.in}
 * ({@code @NamedInterface("port-in")}), чтобы integration/execution могли поймать его, не нарушая
 * границы модуля.</p>
 *
 * <p>Наследуется от {@link IllegalArgumentException}, чтобы единый
 * {@code GlobalExceptionHandler} (см. CLAUDE.md — "единый @ControllerAdvice") маппил его в
 * 400 Bad Request без отдельного {@code @ExceptionHandler}: отсутствие значения переменной —
 * это невалидный входной аргумент запроса на рендеринг, а не внутренняя ошибка сервера.</p>
 */
public class MissingTemplateVariableException extends IllegalArgumentException {

    public MissingTemplateVariableException(String variableName) {
        super("Missing value for template variable: " + variableName);
    }
}
