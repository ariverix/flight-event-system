package ru.protectinfotrans.eca.integration.dto;

/** Результат ручного reprocess DLQ-записи (P2-6). */
public record DeadLetterReprocessResponse(Long dlqId, boolean success, String status) {
}
