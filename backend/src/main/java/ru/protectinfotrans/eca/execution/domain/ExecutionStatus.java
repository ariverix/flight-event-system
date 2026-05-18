package ru.protectinfotrans.eca.execution.domain;

public enum ExecutionStatus {
    RUNNING,
    WAITING,   // заблокирован на шаге WAIT — ждёт критерий или таймаут
    COMPLETED,
    ABORTED
}
