package ru.protectinfotrans.eca.execution.port.out;

import ru.protectinfotrans.eca.sequence.domain.Sequence;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;

import java.util.List;
import java.util.Optional;

/**
 * Порт для чтения последовательностей из Sequence Manager модуля.
 * Execution модуль использует этот порт для получения активных последовательностей
 * и проверки start/stop критериев.
 *
 * См. диплом: раздел 1.4.1 (модульные границы), раздел 1.4.2
 */
public interface SequenceQueryPort {

    /**
     * Получить последовательность по ID.
     */
    Optional<Sequence> findById(Long id);

    /**
     * Получить все активные последовательности.
     * Используется для проверки start критериев при поступлении событий.
     */
    List<Sequence> findAllByStatus(SequenceStatus status);
}
