package ru.protectinfotrans.eca.eventhandling.dto;

import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger;

import java.time.LocalDateTime;

public record EventHandlerResponse(
        Long id,
        HandlerScope scope,
        Long scopeId,
        NotificationTrigger triggerType,
        NotificationChannelType channel,
        String target,
        boolean enabled,
        LocalDateTime createdAt
) {
    public static EventHandlerResponse from(EventHandler h) {
        return new EventHandlerResponse(h.getId(), h.getScope(), h.getScopeId(), h.getTriggerType(),
                h.getChannel(), h.getTarget(), h.isEnabled(), h.getCreatedAt());
    }
}
