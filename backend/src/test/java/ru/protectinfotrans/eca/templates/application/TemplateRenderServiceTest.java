package ru.protectinfotrans.eca.templates.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.templates.domain.Template;
import ru.protectinfotrans.eca.templates.port.in.MissingTemplateVariableException;
import ru.protectinfotrans.eca.templates.port.out.TemplateRepositoryPort;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateRenderService")
class TemplateRenderServiceTest {

    @Mock
    private TemplateRepositoryPort repository;

    private TemplateRenderService service;

    @BeforeEach
    void setUp() {
        service = new TemplateRenderService(repository, new TemplateRenderer());
    }

    private Template template(String body, boolean active) {
        return Template.builder()
                .id(1L)
                .name("REQUEST_POSITION")
                .messageType(MessageType.UPLINK)
                .origin(UplinkOrigin.COMPUTER_GENERATED)
                .category("POSITION")
                .body(body)
                .active(active)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("render: подставляет переменные в найденный шаблон")
    void rendersFoundTemplate() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenReturn(Optional.of(template("Please report position, ETA {{eta}}", true)));

        String result = service.render("REQUEST_POSITION", Map.of("eta", "12:30"));

        assertThat(result).isEqualTo("Please report position, ETA 12:30");
    }

    @Test
    @DisplayName("render: шаблон не найден -> NoSuchElementException")
    void renderThrowsWhenTemplateNotFound() {
        when(repository.findByName("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.render("UNKNOWN", Map.of()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("render: рендерит ВЫКЛЮЧЕННЫЙ шаблон без исключения (мягкое выключение — см. javadoc)")
    void rendersInactiveTemplateWithoutException() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenReturn(Optional.of(template("ETA {{eta}}", false)));

        String result = service.render("REQUEST_POSITION", Map.of("eta", "12:30"));

        assertThat(result).isEqualTo("ETA 12:30");
    }

    @Test
    @DisplayName("render: отсутствующая переменная пробрасывает MissingTemplateVariableException")
    void renderPropagatesMissingVariableException() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenReturn(Optional.of(template("ETA {{eta}}", true)));

        assertThatThrownBy(() -> service.render("REQUEST_POSITION", Map.of()))
                .isInstanceOf(MissingTemplateVariableException.class);
    }

    @Test
    @DisplayName("tryRender: шаблон найден -> Optional с рендерингом")
    void tryRenderReturnsOptionalWithRenderedText() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenReturn(Optional.of(template("ETA {{eta}}", true)));

        Optional<String> result = service.tryRender("REQUEST_POSITION", Map.of("eta", "12:30"));

        assertThat(result).contains("ETA 12:30");
    }

    @Test
    @DisplayName("tryRender: шаблон не найден -> Optional.empty(), без исключения")
    void tryRenderReturnsEmptyWhenTemplateNotFound() {
        when(repository.findByName("UNKNOWN")).thenReturn(Optional.empty());

        Optional<String> result = service.tryRender("UNKNOWN", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("tryRender: шаблон НАЙДЕН, но не хватает переменной -> пробрасывает "
            + "MissingTemplateVariableException (НЕ глушится до Optional.empty() — иначе вызывающий "
            + "не отличит это от легитимного not-found fallback и подменит текст именем шаблона)")
    void tryRenderPropagatesMissingVariableExceptionWhenTemplateFound() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenReturn(Optional.of(template("ETA {{eta}}", true)));

        assertThatThrownBy(() -> service.tryRender("REQUEST_POSITION", Map.of()))
                .isInstanceOf(MissingTemplateVariableException.class);
    }

    @Test
    @DisplayName("tryRender: сбой репозитория (например, БД недоступна) -> Optional.empty(), "
            + "без исключения наружу — лукап шаблона не должен валить вызывающего (durable-доставку)")
    void tryRenderReturnsEmptyWhenRepositoryThrows() {
        when(repository.findByName("REQUEST_POSITION"))
                .thenThrow(new org.springframework.dao.InvalidDataAccessResourceUsageException(
                        "relation \"templates\" does not exist"));

        Optional<String> result = service.tryRender("REQUEST_POSITION", Map.of("eta", "12:30"));

        assertThat(result).isEmpty();
    }
}
