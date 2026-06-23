package ru.protectinfotrans.eca.eventhandling.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;

import java.util.List;

interface EventHandlerJpaRepository extends JpaRepository<EventHandler, Long> {

    List<EventHandler> findByScopeAndScopeId(HandlerScope scope, Long scopeId);
}
