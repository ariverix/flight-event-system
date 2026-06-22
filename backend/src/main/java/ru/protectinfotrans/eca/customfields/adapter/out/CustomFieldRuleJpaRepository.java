package ru.protectinfotrans.eca.customfields.adapter.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;

import java.util.List;
import java.util.Optional;

interface CustomFieldRuleJpaRepository extends JpaRepository<CustomFieldRule, Long> {

    Optional<CustomFieldRule> findByName(String name);

    boolean existsByName(String name);

    @Query("""
            SELECT r FROM CustomFieldRule r
            WHERE (:messageType IS NULL OR r.messageType = :messageType)
              AND (:active IS NULL OR r.active = :active)
            """)
    Page<CustomFieldRule> findAllFiltered(@Param("messageType") MessageType messageType,
                                           @Param("active") Boolean active,
                                           Pageable pageable);

    /**
     * Правило подходит сообщению, если оно активно, совпадает по {@code messageType} и его
     * {@code templateName} либо {@code NULL} (любой шаблон этого типа), либо совпадает с
     * переданным {@code templateName} — см. {@code CustomFieldRuleRepositoryPort#findActiveApplicableRules}.
     */
    @Query("""
            SELECT r FROM CustomFieldRule r
            WHERE r.active = true
              AND r.messageType = :messageType
              AND (r.templateName IS NULL OR r.templateName = :templateName)
            """)
    List<CustomFieldRule> findActiveApplicableRules(@Param("messageType") MessageType messageType,
                                                      @Param("templateName") String templateName);
}
