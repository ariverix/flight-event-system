package ru.protectinfotrans.eca.sequence.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.AuditLog;

/**
 * Spring Data JPA репозиторий для сущности AuditLog.
 */
interface AuditLogJpaRepository extends JpaRepository<AuditLog, Long> {
}
