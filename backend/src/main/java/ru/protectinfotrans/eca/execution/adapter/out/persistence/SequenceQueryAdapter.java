package ru.protectinfotrans.eca.execution.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.execution.port.out.SequenceQueryPort;
import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.util.List;
import java.util.Optional;

/**
 * Адаптер для чтения последовательностей из Sequence Manager модуля.
 * Делегирует вызовы в SequenceRepositoryPort из sequence модуля.
 *
 * См. диплом: раздел 1.4.1 (модульные границы через Named Interfaces)
 */
@Component
@RequiredArgsConstructor
public class SequenceQueryAdapter implements SequenceQueryPort {

    private final SequenceRepositoryPort sequenceRepository;

    @Override
    public Optional<Sequence> findById(Long id) {
        return sequenceRepository.findById(id);
    }

    @Override
    public List<Sequence> findAllByStatus(SequenceStatus status) {
        // Page в List для упрощения (все активные последовательности обычно помещаются на одной странице)
        return sequenceRepository.findByStatus(status, org.springframework.data.domain.Pageable.unpaged())
                .getContent();
    }
}
