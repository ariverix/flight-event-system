package ru.protectinfotrans.eca.sequence.domain;

/**
 * Типы действий для шага ACTION.
 *
 * См. диплом: раздел 1.2.2 (Sequencer — Actions)
 */
public enum ActionType {

    /** Отправить сообщение на борт (uplink) */
    SEND_UPLINK,

    /** Отправить наземное сообщение (ground) */
    SEND_GROUND,

    /** Поднять пользовательское условие (алерт) */
    RAISE_CONDITION,

    /** Снять пользовательское условие */
    CLOSE_CONDITION,

    /** Ждать заданное время */
    WAIT_TIME
}
