package ru.protectinfotrans.eca;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// Живёт в корневом пакете — cross-cutting, используется несколькими модулями
@Repository
public interface AuditLogQueryRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByIdAsc(Pageable pageable);

    Page<AuditLog> findByEntityTypeOrderByIdAsc(String entityType, Pageable pageable);

    Page<AuditLog> findByActionOrderByIdAsc(String action, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndActionOrderByIdAsc(
            String entityType, String action, Pageable pageable);
}
