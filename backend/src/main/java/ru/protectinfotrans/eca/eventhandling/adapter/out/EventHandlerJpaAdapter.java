package ru.protectinfotrans.eca.eventhandling.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.port.out.EventHandlerRepositoryPort;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class EventHandlerJpaAdapter implements EventHandlerRepositoryPort {

    private final EventHandlerJpaRepository jpaRepository;

    @Override
    public EventHandler save(EventHandler handler) {
        return jpaRepository.save(handler);
    }

    @Override
    public Optional<EventHandler> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<EventHandler> findByScope(HandlerScope scope, Long scopeId) {
        return jpaRepository.findByScopeAndScopeId(scope, scopeId);
    }

    @Override
    public void deleteById(Long id) {
        jpaRepository.deleteById(id);
    }
}
