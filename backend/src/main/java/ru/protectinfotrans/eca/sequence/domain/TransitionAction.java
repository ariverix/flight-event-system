package ru.protectinfotrans.eca.sequence.domain;

/** переход после шага: следующий / прыжок / конец / аборт */
public enum TransitionAction {
    CONTINUE,
    GOTO,
    END,
    ABORT
}
