/**
 * Входные порты (Driving Ports) модуля Event Processor.
 * Определяют интерфейсы для взаимодействия с внешним миром.
 *
 * <p>Явный named interface (P2-2): {@link ru.protectinfotrans.eca.eventprocessor.port.in.MessageInputPort}
 * используется как точка входа из модуля {@code integration} (RAW-эндпоинт ARINC 618/620/Type B/AFTN,
 * {@code integration.adapter.in.RawMessageController}) — без этого парсинг сырых сообщений пришлось бы
 * реализовывать внутри {@code eventprocessor}, что создало бы цикл Modulith
 * ({@code eventprocessor -> integration -> execution -> eventprocessor}, так как {@code execution} уже
 * зависит от {@code eventprocessor.event.NormalizedEvent}, а {@code integration} уже зависит от
 * {@code execution.port.out.*}).
 */
@org.springframework.modulith.NamedInterface("port-in")
package ru.protectinfotrans.eca.eventprocessor.port.in;
