/**
 * Модуль Conditions/Alerts Engine (P3-3) — паритет с SITA Sequencer: raise/close custom condition
 * per-flight (борт + номер рейса), независимый уровень алерта (No/Low/Medium/High/Critical),
 * запрет повторного raise активного условия тем же именем, критерий CONDITION_ACTIVE, авто-
 * закрытие активных условий рейса при завершении (стадия IN/SUMMARY).
 *
 * <p><b>Границы Modulith (анализ зависимостей, P3-3):</b> модуль — СИНК, ТОЧНЫЙ структурный
 * аналог {@code customfields} (P3-2, см. её package-info) — НЕ имеет Java-импортов на домен
 * других ECA-модулей.
 * <ul>
 *   <li>{@code execution} ЧИТАЕТ/ПИШЕТ через
 *       {@link ru.protectinfotrans.eca.conditions.port.in.ConditionManagementUseCase} (ACTION-шаг
 *       RAISE_CONDITION/CLOSE_CONDITION, см. {@code ActionStepRule}) и
 *       {@link ru.protectinfotrans.eca.conditions.port.in.ConditionQueryUseCase} (критерий
 *       CONDITION_ACTIVE при сборке {@code ExecutionContext.additionalData}, см.
 *       {@code ExecutionService#buildContext}/{@code buildDefaultContext} — тот же приём, что уже
 *       используется для {@code customFields}/{@code offTime}), БЕЗ обратной Java-зависимости
 *       conditions → execution;</li>
 *   <li>{@code eventprocessor} ВЫЗЫВАЕТ
 *       {@link ru.protectinfotrans.eca.conditions.port.in.FlightConditionLifecycleUseCase} на
 *       смену стадии IN/SUMMARY — авто-закрытие активных условий рейса, ТОТ ЖЕ канал, что уже
 *       используется для {@code customfields.port.in.FlightContextLifecycleUseCase} (см.
 *       {@code MessagePersistenceTransaction#recordFlightStageEvent} и
 *       {@code EventProcessorService#notifyFlightStageChange}).</li>
 * </ul>
 * Направление зависимости везде ОДНОСТОРОННЕЕ "потребитель → conditions.port.in", как и для
 * {@code customfields.port.in}/{@code templates.port.in} — никакого цикла.
 *
 * <p><b>Что заменяет:</b> до этой задачи raise/close/query условий жили в {@code integration}
 * модуле ({@code IntegrationService}, in-memory {@code Map<aircraftId, Set<conditionName>>},
 * через {@code execution.port.out.ConditionQueryPort}/{@code MessageOutputPort#raiseCondition}/
 * {@code #closeCondition}) — НЕ персистентно, НЕ per-flight (только per-aircraft, условие "текло"
 * между рейсами одного борта), без хранения уровня алерта, без авто-закрытия при завершении
 * рейса. Этот модуль — полная замена: {@code execution.port.out.ConditionQueryPort} и
 * {@code MessageOutputPort#raiseCondition}/{@code #closeCondition} удалены, {@code integration}
 * больше не участвует в управлении условиями (условия — внутреннее понятие Sequencer-движка, не
 * исходящий борт/ground канал — в отличие от {@code SEND_UPLINK}/{@code SEND_GROUND}, у
 * RAISE_CONDITION/CLOSE_CONDITION никогда не было материального "отправить во внешнюю систему"
 * эффекта, который оправдывал бы прохождение через {@code integration}).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Conditions/Alerts Engine"
)
package ru.protectinfotrans.eca.conditions;
