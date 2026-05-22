package ru.protectinfotrans.eca.sequence.port.out;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;

import java.util.Optional;

/**
 * Выходной порт для хранения последовательностей.
 *
 */
public interface SequenceRepositoryPort {

    Sequence save(Sequence sequence);

    Optional<Sequence> findById(Long id);

    Page<Sequence> findAll(Pageable pageable);

    Page<Sequence> findByStatus(SequenceStatus status, Pageable pageable);

    void deleteById(Long id);
}
