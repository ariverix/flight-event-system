package ru.protectinfotrans.eca;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// Живёт в корневом пакете — cross-cutting, используется несколькими модулями
@Repository
public interface AuditLogQueryRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditLog> findByEntityTypeOrderByIdDesc(String entityType, Pageable pageable);

    Page<AuditLog> findByActionOrderByIdDesc(String action, Pageable pageable);

    Page<AuditLog> findByEntityTypeAndActionOrderByIdDesc(
            String entityType, String action, Pageable pageable);
}
