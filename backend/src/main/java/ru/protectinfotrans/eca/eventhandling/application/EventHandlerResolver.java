package ru.protectinfotrans.eca.eventhandling.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.Folder;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.port.out.EventHandlerRepositoryPort;
import ru.protectinfotrans.eca.eventhandling.port.out.FolderRepositoryPort;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Разрешение действующих обработчиков событий для последовательности (P3-4, паритет SITA Event
 * Handling: folder-level наследование + sequence-level override).
 *
 * <p><b>Семантика «ближайший уровень с конфигурацией выигрывает»:</b>
 * <ol>
 *   <li>Если у последовательности есть СВОИ ({@code SEQUENCE}-scope) включённые обработчики — берём
 *       их (override), наследование папок игнорируется ПОЛНОСТЬЮ (это и есть «переопределение»).</li>
 *   <li>Иначе идём вверх по дереву папок от папки последовательности к корню; первый уровень папки,
 *       где есть включённые обработчики, выигрывает — берём его (наследование с nearest-wins).</li>
 *   <li>Если конфигурации нет нигде — пустой список (никого не уведомляем).</li>
 * </ol>
 * «Уровень определяет event handling» = у него есть ХОТЬ ОДИН включённый обработчик (независимо от
 * триггера). Среди обработчиков выигравшего уровня к доставке отбираются те, чей
 * {@link ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger} подходит фактическому
 * исходу шага (success/false) — фильтрацию по триггеру делает уже вызывающий
 * ({@code NotificationDispatchService}); этот резолвер возвращает обработчики выигравшего уровня.
 *
 * <p>Защита от циклов/глубины: обход родителей ограничен посещёнными id (на случай повреждённых
 * данных) — нормальное дерево всегда конечно (parentId == null у корня).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventHandlerResolver {

    private final EventHandlerRepositoryPort handlerRepository;
    private final FolderRepositoryPort folderRepository;

    /**
     * Обработчики действующего уровня для последовательности. {@code folderId} — непосредственная
     * папка последовательности (nullable), несётся в {@code StepNotificationEvent}.
     */
    public List<EventHandler> resolve(Long sequenceId, Long folderId) {
        if (sequenceId != null) {
            List<EventHandler> seqHandlers = enabled(handlerRepository.findByScope(HandlerScope.SEQUENCE, sequenceId));
            if (!seqHandlers.isEmpty()) {
                return seqHandlers; // override: уровень последовательности определён — наследование папок отбрасывается
            }
        }

        Long current = folderId;
        Set<Long> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            List<EventHandler> folderHandlers = enabled(handlerRepository.findByScope(HandlerScope.FOLDER, current));
            if (!folderHandlers.isEmpty()) {
                return folderHandlers; // nearest-wins наследование
            }
            current = folderRepository.findById(current).map(Folder::getParentId).orElse(null);
        }
        return List.of();
    }

    private List<EventHandler> enabled(List<EventHandler> handlers) {
        return handlers.stream().filter(EventHandler::isEnabled).toList();
    }
}
