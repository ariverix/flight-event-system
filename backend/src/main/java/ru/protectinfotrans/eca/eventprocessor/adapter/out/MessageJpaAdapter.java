package ru.protectinfotrans.eca.eventprocessor.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;
import ru.protectinfotrans.eca.sequence.domain.PositionSource;

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
    public Optional<IncomingMessage> findByExternalMessageId(String externalMessageId) {
        return jpaRepository.findByExternalMessageId(externalMessageId);
    }

    /**
     * См. javadoc порта. Без явной смены propagation (REQUIRED по умолчанию — присоединяется
     * к транзакции вызывающего, как обычный save). Явный {@code flush()}: constraint violation
     * партиционного unique index (V25) должен материализоваться СЕЙЧАС ЖЕ, внутри транзакции
     * вызывающего метода, а не на коммите — иначе перехватить его внутри вызывающего метода
     * (и откатить транзакцию штатно через Spring AOP-прокси) было бы невозможно.
     */
    @Override
    public IncomingMessage saveAndFlush(IncomingMessage message) {
        IncomingMessage saved = jpaRepository.save(message);
        jpaRepository.flush();
        return saved;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IncomingMessage> findByExternalMessageIdInNewTransaction(String externalMessageId) {
        return jpaRepository.findByExternalMessageId(externalMessageId);
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
    public boolean existsActualPositionReportSince(
            String aircraftId,
            LocalDateTime sinceTime,
            PositionSource source,
            LocalDateTime afterTime
    ) {
        // PostgreSQL 42P18: тот же workaround что и для existsByAircraftAndTypeAndTemplate —
        // два отдельных запроса вместо одного с nullable afterTime-параметром.
        if (afterTime == null) {
            return jpaRepository.existsActualPositionReportSinceAnyPoint(aircraftId, sinceTime, source);
        }
        return jpaRepository.existsActualPositionReportSinceAfterPoint(aircraftId, sinceTime, source, afterTime);
    }

    @Override
    public Optional<LocalDateTime> findLastActualPositionReportTime(String aircraftId, PositionSource source) {
        return jpaRepository.findLastActualPositionReportTime(aircraftId, source);
    }

    @Override
    public Page<AircraftSummaryResponse> findAircraftSummaries(String search, Pageable pageable) {
        // отдельные методы с/без поиска — как в findAllWithFilters (без nullable-параметра)
        if (search == null || search.isBlank()) {
            return jpaRepository.findAircraftSummaries(pageable);
        }
        return jpaRepository.searchAircraftSummaries(search.trim(), pageable);
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
