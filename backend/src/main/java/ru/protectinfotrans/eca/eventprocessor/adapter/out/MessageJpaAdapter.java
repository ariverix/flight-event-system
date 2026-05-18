package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;

import java.time.LocalDateTime;
import java.util.Optional;

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
        // PostgreSQL 42P18: передача null в параметризованный IS NULL вызывает ошибку типа.
        // Решение — два отдельных запроса вместо одного с nullable-параметром.
        if (afterTime == null) {
            return jpaRepository.existsByAircraftAndTypeAndTemplateAnyTime(aircraftId, messageType, templateName);
        }
        return jpaRepository.existsByAircraftAndTypeAndTemplateAfter(aircraftId, messageType, templateName, afterTime);
    }

    @Override
    public boolean existsPositionReportWithinMinutes(String aircraftId, int minutesAgo) {
        LocalDateTime sinceTime = LocalDateTime.now().minusMinutes(minutesAgo);
        return jpaRepository.existsPositionReportWithinMinutes(aircraftId, sinceTime);
    }

    @Override
    public Page<IncomingMessage> findAllWithFilters(String aircraftId, MessageType messageType, Pageable pageable) {
        if (aircraftId != null && messageType != null) {
            return jpaRepository.findByAircraftIdAndMessageType(aircraftId, messageType, pageable);
        } else if (aircraftId != null) {
            return jpaRepository.findByAircraftId(aircraftId, pageable);
        } else if (messageType != null) {
            return jpaRepository.findByMessageType(messageType, pageable);
        } else {
            return jpaRepository.findAll(pageable);
        }
    }
}
