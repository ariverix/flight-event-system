/**
 * Входные порты модуля Conditions Engine, потребляемые соседними модулями (см.
 * {@code conditions.package-info} для полной карты границ Modulith):
 * {@code ConditionManagementUseCase}/{@code ConditionQueryUseCase} ({@code execution} — ACTION
 * raise/close + критерий CONDITION_ACTIVE); {@code FlightConditionLifecycleUseCase}
 * ({@code eventprocessor} — авто-закрытие на смене стадии IN/SUMMARY, точный аналог
 * {@code customfields.port.in.FlightContextLifecycleUseCase}).
 *
 * <p>{@code @NamedInterface("port-in")} — без этой аннотации {@code ApplicationModules.verify()}
 * считает пакет НЕэкспортированным (всё, что не названный интерфейс, по умолчанию internal для
 * модуля) и репортит каждое обращение {@code execution}/{@code eventprocessor} как нарушение
 * границы — тот же приём, что у {@code customfields.port.in}/{@code templates.port.in}.
 */
@org.springframework.modulith.NamedInterface("port-in")
package ru.protectinfotrans.eca.conditions.port.in;
