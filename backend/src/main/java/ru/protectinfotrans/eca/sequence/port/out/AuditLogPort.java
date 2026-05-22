package ru.protectinfotrans.eca.sequence.port.out;

import ru.protectinfotrans.eca.AuditLog;

/**
 * Выходной порт для записи аудита из модуля Sequence Manager.
 *
 */
public interface AuditLogPort {

    void save(AuditLog auditLog);
}
