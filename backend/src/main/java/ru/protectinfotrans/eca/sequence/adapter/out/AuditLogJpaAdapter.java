package ru.protectinfotrans.eca.sequence.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.sequence.port.out.AuditLogPort;

/**
 * JPA-адаптер для записи аудита в PostgreSQL.
 *
 */
@Repository("sequenceAuditLogJpaAdapter")
@RequiredArgsConstructor
public class AuditLogJpaAdapter implements AuditLogPort {

    private final AuditLogJpaRepository jpaRepository;

    @Override
    public void save(AuditLog auditLog) {
        jpaRepository.save(auditLog);
    }
}
