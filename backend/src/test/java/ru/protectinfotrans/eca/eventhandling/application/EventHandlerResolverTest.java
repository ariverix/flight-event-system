package ru.protectinfotrans.eca.eventhandling.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.eventhandling.domain.EventHandler;
import ru.protectinfotrans.eca.eventhandling.domain.Folder;
import ru.protectinfotrans.eca.eventhandling.domain.HandlerScope;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationChannelType;
import ru.protectinfotrans.eca.eventhandling.domain.NotificationTrigger;
import ru.protectinfotrans.eca.eventhandling.port.out.EventHandlerRepositoryPort;
import ru.protectinfotrans.eca.eventhandling.port.out.FolderRepositoryPort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventHandlerResolver — наследование папок + override последовательности (P3-4)")
class EventHandlerResolverTest {

    @Mock
    private EventHandlerRepositoryPort handlerRepository;
    @Mock
    private FolderRepositoryPort folderRepository;
    @InjectMocks
    private EventHandlerResolver resolver;

    private EventHandler handler(Long id, HandlerScope scope, Long scopeId, boolean enabled) {
        return EventHandler.builder()
                .id(id).scope(scope).scopeId(scopeId)
                .triggerType(NotificationTrigger.ON_ANY)
                .channel(NotificationChannelType.EMAIL)
                .target("ops@example.com")
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("есть обработчики уровня последовательности -> override, папки не запрашиваются")
    void sequenceLevelOverridesFolder() {
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L))
                .thenReturn(List.of(handler(1L, HandlerScope.SEQUENCE, 7L, true)));

        List<EventHandler> result = resolver.resolve(7L, 99L);

        assertThat(result).extracting(EventHandler::getId).containsExactly(1L);
        verify(handlerRepository, never()).findByScope(eq(HandlerScope.FOLDER), any());
    }

    @Test
    @DisplayName("нет обработчиков последовательности -> наследуются от непосредственной папки")
    void inheritsFromImmediateFolder() {
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L)).thenReturn(List.of());
        when(handlerRepository.findByScope(HandlerScope.FOLDER, 99L))
                .thenReturn(List.of(handler(2L, HandlerScope.FOLDER, 99L, true)));

        List<EventHandler> result = resolver.resolve(7L, 99L);

        assertThat(result).extracting(EventHandler::getId).containsExactly(2L);
    }

    @Test
    @DisplayName("непосредственная папка пуста -> обход вверх к родителю (nearest-wins)")
    void walksUpToParentFolder() {
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L)).thenReturn(List.of());
        when(handlerRepository.findByScope(HandlerScope.FOLDER, 99L)).thenReturn(List.of());
        when(folderRepository.findById(99L))
                .thenReturn(Optional.of(Folder.builder().id(99L).name("child").parentId(50L).build()));
        when(handlerRepository.findByScope(HandlerScope.FOLDER, 50L))
                .thenReturn(List.of(handler(3L, HandlerScope.FOLDER, 50L, true)));

        List<EventHandler> result = resolver.resolve(7L, 99L);

        assertThat(result).extracting(EventHandler::getId).containsExactly(3L);
    }

    @Test
    @DisplayName("конфигурации нет нигде -> пусто")
    void noHandlersAnywhere() {
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L)).thenReturn(List.of());
        when(handlerRepository.findByScope(HandlerScope.FOLDER, 99L)).thenReturn(List.of());
        when(folderRepository.findById(99L))
                .thenReturn(Optional.of(Folder.builder().id(99L).name("root").parentId(null).build()));

        assertThat(resolver.resolve(7L, 99L)).isEmpty();
    }

    @Test
    @DisplayName("выключенные обработчики игнорируются; уровень с только выключенными не выигрывает")
    void disabledHandlersAreIgnored() {
        // у последовательности только выключенный -> не override, проваливаемся в наследование папки
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L))
                .thenReturn(List.of(handler(1L, HandlerScope.SEQUENCE, 7L, false)));
        when(handlerRepository.findByScope(HandlerScope.FOLDER, 99L))
                .thenReturn(List.of(handler(2L, HandlerScope.FOLDER, 99L, true)));

        List<EventHandler> result = resolver.resolve(7L, 99L);

        assertThat(result).extracting(EventHandler::getId).containsExactly(2L);
    }

    @Test
    @DisplayName("последовательность вне папок (folderId == null) и без своих обработчиков -> пусто")
    void noFolderNoSequenceHandlers() {
        when(handlerRepository.findByScope(HandlerScope.SEQUENCE, 7L)).thenReturn(List.of());

        assertThat(resolver.resolve(7L, null)).isEmpty();
        verify(handlerRepository, never()).findByScope(eq(HandlerScope.FOLDER), any());
    }
}
