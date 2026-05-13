package ru.protectinfotrans.eca.sequence.adapter.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;

/**
 * Spring Data JPA репозиторий для сущности Sequence.
 */
interface SequenceJpaRepository extends JpaRepository<Sequence, Long> {

    Page<Sequence> findByStatus(SequenceStatus status, Pageable pageable);
}
