package ru.protectinfotrans.eca.eventhandling.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerResponse;
import ru.protectinfotrans.eca.eventhandling.port.in.EventHandlerManagementUseCase;
import ru.protectinfotrans.eca.eventhandling.port.out.EventHandlerRepositoryPort;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EventHandlerService implements EventHandlerManagementUseCase {

    private final EventHandlerRepositoryPort handlerRepository;

    @Override
    public EventHandlerResponse createHandler(EventHandlerCreateRequest request) {
        EventHandler saved = handlerRepository.save(EventHandler.builder()
                .scope(request.scope())
                .scopeId(request.scopeId())
                .triggerType(request.triggerType())
                .channel(request.channel())
                .target(request.target())
                .enabled(true)
                .build());
        log.info("Создан обработчик событий id={} scope={}/{} trigger={} channel={} target='{}'",
                saved.getId(), saved.getScope(), saved.getScopeId(), saved.getTriggerType(),
                saved.getChannel(), saved.getTarget());
        return EventHandlerResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventHandlerResponse> listHandlers(HandlerScope scope, Long scopeId) {
        return handlerRepository.findByScope(scope, scopeId).stream()
                .map(EventHandlerResponse::from).toList();
    }

    @Override
    public void deleteHandler(Long id) {
        handlerRepository.deleteById(id);
        log.info("Удалён обработчик событий id={}", id);
    }
}
