package ru.protectinfotrans.eca.eventprocessor.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.eventprocessor.domain.IncomingMessage;
import ru.protectinfotrans.eca.eventprocessor.port.out.MessageRepositoryPort;

/**
 * Сервис для запросов к журналу сообщений.
 *
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageRepositoryPort messageRepository;

    /**
     * Получить список сообщений с фильтрами и пагинацией.
     *
     * @param aircraftId фильтр по ВС (опционально)
     * @param messageType фильтр по типу сообщения (опционально)
     * @param pageable параметры пагинации
     * @return страница сообщений
     */
    public Page<IncomingMessage> findMessages(
            String aircraftId,
            MessageType messageType,
            Pageable pageable
    ) {
        return messageRepository.findAllWithFilters(aircraftId, messageType, pageable);
    }
}
