package ru.protectinfotrans.eca.eventhandling.port.out;

import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;

import java.util.List;
import java.util.Optional;

public interface EventHandlerRepositoryPort {

    EventHandler save(EventHandler handler);

    Optional<EventHandler> findById(Long id);

    /** Все обработчики уровня (включая выключенные — фильтрацию enabled делает вызывающий). */
    List<EventHandler> findByScope(HandlerScope scope, Long scopeId);

    void deleteById(Long id);
}
