package ru.protectinfotrans.eca.user.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.CorrelationContext;
import ru.protectinfotrans.eca.user.port.out.AuditLogPort;

/**
 * JPA-адаптер для записи аудита пользовательских операций в PostgreSQL.
 *
 * <p>P4-5: correlationId проставляется ЗДЕСЬ — в единственной точке записи аудита — из
 * {@link CorrelationContext} (MDC текущего HTTP-запроса, см. {@code CorrelationIdFilter}), если
 * вызывающий не задал его явно. Так все писатели аудита (AuthController/UserService/
 * SequenceService) автоматически получают связь записи аудита со структурными логами по
 * сквозному id, без дублирования логики в каждом builder'е.
 */
@Repository("userAuditLogJpaAdapter")
@RequiredArgsConstructor
public class AuditLogJpaAdapter implements AuditLogPort {

    private final UserAuditLogJpaRepository jpaRepository;

    @Override
    public void save(AuditLog auditLog) {
        if (auditLog.getCorrelationId() == null) {
            auditLog.setCorrelationId(CorrelationContext.getCorrelationId());
        }
        jpaRepository.save(auditLog);
    }
}
