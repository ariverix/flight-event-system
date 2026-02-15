package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * JPA-адаптер для хранения входящих сообщений в PostgreSQL.
 *
 * См. диплом: раздел 1.4.4, таблица 1.6
 */
@Repository
@RequiredArgsConstructor
public class MessageJpaAdapter implements MessageRepositoryPort {

    private final MessageJpaRepository jpaRepository;

    @Override
    public IncomingMessage save(IncomingMessage message) {
        return jpaRepository.save(message);
    }

    @Override
    public Optional<IncomingMessage> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public boolean existsByAircraftAndTypeAndTemplate(
            String aircraftId,
            MessageType messageType,
            String templateName,
            LocalDateTime afterTime
    ) {
        return jpaRepository.existsByAircraftAndTypeAndTemplate(aircraftId, messageType, templateName, afterTime);
    }

    @Override
    public boolean existsPositionReportWithinMinutes(String aircraftId, int minutesAgo) {
        LocalDateTime sinceTime = LocalDateTime.now().minusMinutes(minutesAgo);
        return jpaRepository.existsPositionReportWithinMinutes(aircraftId, sinceTime);
    }
}
