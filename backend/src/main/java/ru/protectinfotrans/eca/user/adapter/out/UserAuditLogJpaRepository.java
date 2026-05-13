package ru.protectinfotrans.eca.user.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.AuditLog;

/**
 * JPA-репозиторий для AuditLog (user module).
 */
public interface UserAuditLogJpaRepository extends JpaRepository<AuditLog, Long> {
}
