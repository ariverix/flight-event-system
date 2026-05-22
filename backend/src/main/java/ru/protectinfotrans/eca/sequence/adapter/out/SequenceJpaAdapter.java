package ru.protectinfotrans.eca.sequence.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.util.Optional;

/**
 * JPA-адаптер для хранения последовательностей в PostgreSQL.
 *
 */
@Repository
@RequiredArgsConstructor
public class SequenceJpaAdapter implements SequenceRepositoryPort {

    private final SequenceJpaRepository jpaRepository;

    @Override
    public Sequence save(Sequence sequence) {
        return jpaRepository.save(sequence);
    }

    @Override
    public Optional<Sequence> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Page<Sequence> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Page<Sequence> findByStatus(SequenceStatus status, Pageable pageable) {
        return jpaRepository.findByStatus(status, pageable);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }
}
