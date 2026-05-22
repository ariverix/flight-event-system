/**
 * Выходные порты (Driven Ports) модуля Execution Engine.
 * Эти интерфейсы вызываются доменной логикой execution модуля,
 * а реализуются адаптерами в других модулях (например, integration).
 *
 * Named Interface делает этот пакет видимым для других модулей.
 *
 */
@org.springframework.modulith.NamedInterface("port-out")
package ru.protectinfotrans.eca.execution.port.out;
