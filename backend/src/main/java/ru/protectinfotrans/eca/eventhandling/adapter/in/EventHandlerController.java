package ru.protectinfotrans.eca.eventhandling.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.EventHandlerResponse;
import ru.protectinfotrans.eca.eventhandling.port.in.EventHandlerManagementUseCase;

import java.util.List;

@Tag(name = "Event Handlers", description = "Обработчики событий: уведомления уровня папки/последовательности (P3-4)")
@RestController
@RequestMapping("/api/v1/event-handlers")
@RequiredArgsConstructor
public class EventHandlerController {

    private final EventHandlerManagementUseCase handlerUseCase;

    @Operation(summary = "Создать обработчик событий")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public EventHandlerResponse create(@Valid @RequestBody EventHandlerCreateRequest request) {
        return handlerUseCase.createHandler(request);
    }

    @Operation(summary = "Список обработчиков уровня", description = "По scope (FOLDER|SEQUENCE) и scopeId.")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public List<EventHandlerResponse> list(@RequestParam HandlerScope scope, @RequestParam Long scopeId) {
        return handlerUseCase.listHandlers(scope, scopeId);
    }

    @Operation(summary = "Удалить обработчик событий")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public void delete(@PathVariable Long id) {
        handlerUseCase.deleteHandler(id);
    }
}
