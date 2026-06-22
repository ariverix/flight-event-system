package ru.protectinfotrans.eca.templates.application;

import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;

/**
 * Валидация инварианта origin/messageType — паритет с SITA Sequencer:
 * DOWNLINK-шаблон описывает ПОЛУЧЕННОЕ сообщение (нет понятия "происхождение формирования"),
 * UPLINK/GROUND — формируются Sequencer'ом и ОБЯЗАНЫ иметь явный режим источника
 * (computer-generated | from external user / "when triggered by the Sequencer").
 */
@Component
public class TemplateValidator {

    public void validateOriginConsistency(MessageType messageType, UplinkOrigin origin) {
        if (messageType == MessageType.DOWNLINK && origin != null) {
            throw new IllegalArgumentException(
                    "DOWNLINK template must not have an origin (received messages have no "
                            + "computer-generated/external-user formation mode)");
        }
        if (messageType != MessageType.DOWNLINK && origin == null) {
            throw new IllegalArgumentException(
                    messageType + " template requires origin (COMPUTER_GENERATED or EXTERNAL_USER)");
        }
    }
}
