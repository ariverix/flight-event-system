/**
 * Выходные порты (Driven Ports) модуля Execution Engine.
 * Эти интерфейсы вызываются доменной логикой execution модуля,
 * а реализуются адаптерами в других модулях (например, integration).
 *
 * Named Interface делает этот пакет видимым для других модулей.
 *
 * См. диплом: раздел 1.4.1 (Гексагональная архитектура), раздел 1.4.4, таблица 1.6
 */
@org.springframework.modulith.NamedInterface("port-out")
package ru.protectinfotrans.eca.execution.port.out;
