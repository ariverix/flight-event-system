/**
 * Доменная модель модуля Templates Engine — сущность Template (имя/тип/origin/категория/тело).
 * Экспортируется как именованный интерфейс для доступа из других модулей (тестов/будущих
 * адаптеров), хотя основной публичный контракт для execution/integration —
 * {@code templates.port.in.TemplateRenderUseCase} / {@code templates.port.out.TemplateLookupPort}.
 */
@org.springframework.modulith.NamedInterface("domain")
package ru.protectinfotrans.eca.templates.domain;
