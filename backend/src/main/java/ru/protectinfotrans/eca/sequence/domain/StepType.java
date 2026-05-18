package ru.protectinfotrans.eca.sequence.domain;

public enum StepType {
    ACTION,    // единичное действие: send message, raise/close condition, wait time
    EVALUATE,  // мгновенная проверка критерия — точка ветвления
    WAIT       // ожидание реализации критерия с таймаутом
}
