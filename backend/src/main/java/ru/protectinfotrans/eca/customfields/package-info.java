/**
 * Модуль Custom Fields Engine — паритет с SITA Sequencer: правила извлечения именованных полей
 * из входящих сообщений (ACARS downlink/uplink/ground), per-flight хранение извлечённых значений
 * (по борту + номеру рейса), подстановка значений в исходящие шаблоны ({@code {{customField.X}}},
 * заготовка P3-1) и в критерии ECA (через {@code ExecutionContext.additionalData}, тот же канал,
 * которым уже пользуются {@code activeConditions}/{@code offTime}), закрытие контекста рейса при
 * завершении (стадия IN/SUMMARY).
 *
 * <p><b>Границы Modulith (анализ зависимостей, P3-2):</b> модуль — СИНК, как {@code eventprocessor}
 * и {@code templates} (см. их package-info) — НЕ имеет Java-импортов на домен других ECA-модулей.
 * <ul>
 *   <li>{@code eventprocessor} ВЫЗЫВАЕТ {@link ru.protectinfotrans.eca.customfields.port.in.CustomFieldExtractionUseCase}
 *       (после persist каждого входящего сообщения — передаёт уже извлечённые скаляры:
 *       aircraftId/flightNumber/messageType/templateName/content/metadata, БЕЗ обратной Java-
 *       зависимости customfields → eventprocessor) и
 *       {@link ru.protectinfotrans.eca.customfields.port.in.FlightContextLifecycleUseCase}
 *       (на смену стадии IN/SUMMARY — закрытие контекста рейса);</li>
 *   <li>{@code execution} ЧИТАЕТ {@link ru.protectinfotrans.eca.customfields.port.in.CustomFieldQueryUseCase}
 *       при сборке {@code ExecutionContext.additionalData} (критерии) и при объединении переменных
 *       перед рендерингом шаблона ACTION-шага (send uplink/ground). Значения custom fields
 *       вмёрживаются в params шага на этапе ВЫПОЛНЕНИЯ (ActionStepRule), фиксируются в
 *       {@code OutboundMessage#paramsJson} и далее рендерятся durable-поллером из замороженных
 *       params — {@code integration} НЕ обращается к customfields повторно (это и обеспечивает
 *       детерминизм рендеринга при retry).</li>
 * </ul>
 * Направление зависимости везде ОДНОСТОРОННЕЕ "потребитель → customfields.port.in" (NamedInterface),
 * как и для {@code templates.port.in}/{@code eventprocessor.port.out} — никакого цикла.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Custom Fields Engine"
)
package ru.protectinfotrans.eca.customfields;
