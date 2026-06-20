package ru.protectinfotrans.eca.integration.parser;

import ru.protectinfotrans.eca.MessageType;

import java.util.Map;

/**
 * Результат нормализации сырого сообщения «борт-земля» (ARINC 618/620, Type B, AFTN)
 * в структуру, готовую для {@code MessageInputPort#receiveMessage} (P2-1).
 *
 * @param messageType       направление (DOWNLINK борт->земля / UPLINK земля->борт / GROUND земля->земля),
 *                          выводится парсером из формата+направления конкретной телеграммы
 * @param templateName      тип/label сообщения (например {@code OOOI}, {@code POSITION_REPORT},
 *                          {@code FREE_TEXT}, ARINC label, либо тип телеграммы AFTN/Type B)
 * @param aircraftId        регистрация ВС (tail number, AN) — извлечена из тела/заголовка,
 *                          может быть {@code null} если телеграмма привязана только по FI
 *                          (flight id) без хвостового номера — это допустимо по паритету SITA
 *                          (привязка AN ИЛИ FI+flight data)
 * @param flightNumber      номер рейса либо позывной (callsign) как он встретился в сообщении —
 *                          сопоставление позывного с FI через callsign matching table — P2-4,
 *                          здесь это просто извлечённое поле, не разрешённое
 * @param payload           полезная нагрузка (свободный текст сообщения после служебных полей)
 * @param externalMessageId идентификатор сообщения из самого формата (ARINC message reference,
 *                          AFTN serial number) — ключ идемпотентности шлюза (P2-1), может быть
 *                          {@code null} если формат/конкретное сообщение его не несёт
 * @param metadata          доп. поля (координаты позиции, OOOI-метка времени, источник позиции
 *                          и т.п.) в формате, совместимом с {@code MessagePersistenceTransaction}
 *                          (ключи {@code latitude}/{@code longitude}/{@code positionSource}/
 *                          {@code flightStage} распознаются существующей логикой без изменений)
 */
public record ParsedMessage(
        MessageType messageType,
        String templateName,
        String aircraftId,
        String flightNumber,
        String payload,
        String externalMessageId,
        Map<String, Object> metadata
) {
}
