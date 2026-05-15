package ru.protectinfotrans.eca;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Общий JPA-репозиторий для запросов к журналу аудита.
 * Используется контроллером мониторинга (не принадлежит ни одному модулю —
 * расположен в корневом пакете как cross-cutting инфраструктура).
 *
 * См. диплом: раздел 1.3.4 (AuditLog — журнал аудита)
 */
@Repository
public interface AuditLogQueryRepository extends JpaRepository<AuditLog, Long> {

    /** Все записи, сортировка: ID по возрастанию (старые сначала) */
    Page<AuditLog> findAllByOrderByIdAsc(Pageable pageable);

    /** Записи по типу сущности */
    Page<AuditLog> findByEntityTypeOrderByIdAsc(String entityType, Pageable pageable);

    /** Записи по типу операции */
    Page<AuditLog> findByActionOrderByIdAsc(String action, Pageable pageable);

    /** Записи по типу сущности И операции */
    Page<AuditLog> findByEntityTypeAndActionOrderByIdAsc(
            String entityType, String action, Pageable pageable);
}
