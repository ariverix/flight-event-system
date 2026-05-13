package ru.protectinfotrans.eca.sequence.port.out;

import ru.protectinfotrans.eca.AuditLog;

/**
 * Выходной порт для записи аудита из модуля Sequence Manager.
 *
 * См. диплом: раздел 1.3.4 (AuditLog — журнал аудита)
 */
public interface AuditLogPort {

    void save(AuditLog auditLog);
}
