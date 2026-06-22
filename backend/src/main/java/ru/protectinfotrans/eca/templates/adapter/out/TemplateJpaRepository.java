package ru.protectinfotrans.eca.templates.adapter.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.templates.domain.Template;

import java.util.Optional;

interface TemplateJpaRepository extends JpaRepository<Template, Long> {

    Optional<Template> findByName(String name);

    boolean existsByName(String name);

    @Query("""
            SELECT t FROM Template t
            WHERE (:messageType IS NULL OR t.messageType = :messageType)
              AND (:category IS NULL OR t.category = :category)
              AND (:active IS NULL OR t.active = :active)
            """)
    Page<Template> findAllFiltered(@Param("messageType") MessageType messageType,
                                    @Param("category") String category,
                                    @Param("active") Boolean active,
                                    Pageable pageable);
}
