package ru.protectinfotrans.eca.user.port.out;

import ru.protectinfotrans.eca.AuditLog;

/**
 * Выходной порт для записи аудита из модуля User.
 *
 */
public interface AuditLogPort {

    void save(AuditLog auditLog);
}
