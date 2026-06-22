/**
 * Входные порты модуля Custom Fields Engine:
 * {@code CustomFieldRuleManagementUseCase} (CRUD правил, потребляется REST-контроллером этого же
 * модуля); {@code CustomFieldExtractionUseCase}/{@code FlightContextLifecycleUseCase} (вызываются
 * {@code eventprocessor} на приёме сообщения/смене стадии полёта); {@code CustomFieldQueryUseCase}
 * (читается {@code execution}/{@code integration} для подстановки в шаблоны и критерии) — ГЛАВНЫЕ
 * публичные контракты для остальной системы.
 */
@org.springframework.modulith.NamedInterface("port-in")
package ru.protectinfotrans.eca.customfields.port.in;
