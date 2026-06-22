/**
 * Входные порты модуля Templates Engine — {@code TemplateManagementUseCase} (CRUD, потребляется
 * REST-контроллером этого же модуля) и {@code TemplateRenderUseCase} (рендеринг по имени,
 * ГЛАВНЫЙ публичный контракт для execution/integration — единственная точка, через которую
 * другие модули обращаются к движку шаблонов).
 */
@org.springframework.modulith.NamedInterface("port-in")
package ru.protectinfotrans.eca.templates.port.in;
