package ru.protectinfotrans.eca.user.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.user.port.out.AuditLogPort;

/**
 * JPA-адаптер для записи аудита пользовательских операций в PostgreSQL.
 *
 * См. диплом: раздел 1.3.4 (AuditLog — журнал аудита), раздел 1.4.1 (гексагональная архитектура)
 */
@Repository("userAuditLogJpaAdapter")
@RequiredArgsConstructor
public class AuditLogJpaAdapter implements AuditLogPort {

    private final UserAuditLogJpaRepository jpaRepository;

    @Override
    public void save(AuditLog auditLog) {
        jpaRepository.save(auditLog);
    }
}
