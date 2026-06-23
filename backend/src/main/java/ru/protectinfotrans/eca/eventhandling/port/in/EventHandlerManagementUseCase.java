package ru.protectinfotrans.eca.eventhandling.port.in;

import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerResponse;

import java.util.List;

/** CRUD обработчиков событий (P3-4). */
public interface EventHandlerManagementUseCase {

    EventHandlerResponse createHandler(EventHandlerCreateRequest request);

    List<EventHandlerResponse> listHandlers(HandlerScope scope, Long scopeId);

    void deleteHandler(Long id);
}
