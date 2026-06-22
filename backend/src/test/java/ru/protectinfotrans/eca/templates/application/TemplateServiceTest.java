package ru.protectinfotrans.eca.templates.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.templates.domain.Template;
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateUpdateRequest;
import ru.protectinfotrans.eca.templates.port.out.TemplateRepositoryPort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateService")
class TemplateServiceTest {

    @Mock
    private TemplateRepositoryPort repository;

    private final TemplateValidator validator = new TemplateValidator();
    private final TemplateRenderer renderer = new TemplateRenderer();

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(repository, validator, renderer);
    }

    private Template existingUplinkTemplate() {
        return Template.builder()
                .id(1L)
                .name("REQUEST_POSITION")
                .description("Request current position")
                .messageType(MessageType.UPLINK)
                .origin(UplinkOrigin.COMPUTER_GENERATED)
                .category("POSITION")
                .body("Please report position, ETA {{eta}}")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("создаёт UPLINK-шаблон с origin, активный по умолчанию")
        void createsUplinkTemplate() {
            TemplateCreateRequest request = new TemplateCreateRequest(
                    "REQUEST_POSITION", "Request position", MessageType.UPLINK,
                    UplinkOrigin.COMPUTER_GENERATED, "POSITION", "Please report position, ETA {{eta}}", null);

            when(repository.existsByName("REQUEST_POSITION")).thenReturn(false);
            when(repository.save(any(Template.class))).thenAnswer(inv -> {
                Template t = inv.getArgument(0);
                t.setId(1L);
                t.setCreatedAt(LocalDateTime.now());
                t.setUpdatedAt(LocalDateTime.now());
                return t;
            });

            TemplateResponse response = service.create(request);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.name()).isEqualTo("REQUEST_POSITION");
            assertThat(response.messageType()).isEqualTo(MessageType.UPLINK);
            assertThat(response.origin()).isEqualTo(UplinkOrigin.COMPUTER_GENERATED);
            assertThat(response.active()).isTrue();
            assertThat(response.variableNames()).containsExactly("eta");

            ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("создаёт DOWNLINK-шаблон без origin")
        void createsDownlinkTemplateWithoutOrigin() {
            TemplateCreateRequest request = new TemplateCreateRequest(
                    "POS_REPORT", null, MessageType.DOWNLINK, null, null, "Position: {{lat}},{{lon}}", true);

            when(repository.existsByName("POS_REPORT")).thenReturn(false);
            when(repository.save(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

            TemplateResponse response = service.create(request);

            assertThat(response.messageType()).isEqualTo(MessageType.DOWNLINK);
            assertThat(response.origin()).isNull();
            assertThat(response.category()).isEqualTo("GENERAL"); // null category -> default normalization
        }

        @Test
        @DisplayName("дублирующееся имя -> IllegalStateException, save не вызывается")
        void duplicateNameThrows() {
            TemplateCreateRequest request = new TemplateCreateRequest(
                    "REQUEST_POSITION", null, MessageType.UPLINK, UplinkOrigin.COMPUTER_GENERATED,
                    null, "body {{x}}", true);

            when(repository.existsByName("REQUEST_POSITION")).thenReturn(true);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REQUEST_POSITION");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("UPLINK без origin -> IllegalArgumentException (через TemplateValidator)")
        void uplinkWithoutOriginThrows() {
            TemplateCreateRequest request = new TemplateCreateRequest(
                    "BAD_TEMPLATE", null, MessageType.UPLINK, null, null, "body", true);

            when(repository.existsByName("BAD_TEMPLATE")).thenReturn(false);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("get/getByName")
    class Get {

        @Test
        @DisplayName("возвращает шаблон по id")
        void getsById() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingUplinkTemplate()));

            TemplateResponse response = service.get(1L);

            assertThat(response.name()).isEqualTo("REQUEST_POSITION");
        }

        @Test
        @DisplayName("id не найден -> NoSuchElementException")
        void getByIdNotFoundThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(99L)).isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("возвращает шаблон по имени")
        void getsByName() {
            when(repository.findByName("REQUEST_POSITION")).thenReturn(Optional.of(existingUplinkTemplate()));

            TemplateResponse response = service.getByName("REQUEST_POSITION");

            assertThat(response.id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("имя не найдено -> NoSuchElementException")
        void getByNameNotFoundThrows() {
            when(repository.findByName("UNKNOWN")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getByName("UNKNOWN")).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("list")
    class ListTemplates {

        @Test
        @DisplayName("возвращает страницу с маппингом PageResponse")
        void returnsPagedResponse() {
            Page<Template> page = new PageImpl<>(List.of(existingUplinkTemplate()), PageRequest.of(0, 20), 1);
            when(repository.findAll(MessageType.UPLINK, null, null, PageRequest.of(0, 20))).thenReturn(page);

            PageResponse<TemplateResponse> result = service.list(0, 20, "UPLINK", null, null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("обновляет тело/категорию/origin/активность, имя не меняется")
        void updatesMutableFields() {
            Template existing = existingUplinkTemplate();
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(Template.class))).thenAnswer(inv -> inv.getArgument(0));

            TemplateUpdateRequest request = new TemplateUpdateRequest(
                    "Updated description", MessageType.UPLINK, UplinkOrigin.EXTERNAL_USER,
                    "WEATHER", "New body {{x}}", false);

            TemplateResponse response = service.update(1L, request);

            assertThat(response.name()).isEqualTo("REQUEST_POSITION"); // unchanged
            assertThat(response.description()).isEqualTo("Updated description");
            assertThat(response.origin()).isEqualTo(UplinkOrigin.EXTERNAL_USER);
            assertThat(response.category()).isEqualTo("WEATHER");
            assertThat(response.body()).isEqualTo("New body {{x}}");
            assertThat(response.active()).isFalse();
        }

        @Test
        @DisplayName("id не найден -> NoSuchElementException, save не вызывается")
        void updateNotFoundThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            TemplateUpdateRequest request = new TemplateUpdateRequest(
                    null, MessageType.UPLINK, UplinkOrigin.COMPUTER_GENERATED, null, "body", true);

            assertThatThrownBy(() -> service.update(99L, request)).isInstanceOf(NoSuchElementException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("несогласованный origin/messageType -> IllegalArgumentException, save не вызывается")
        void updateWithInconsistentOriginThrows() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingUplinkTemplate()));

            TemplateUpdateRequest request = new TemplateUpdateRequest(
                    null, MessageType.DOWNLINK, UplinkOrigin.COMPUTER_GENERATED, null, "body", true);

            assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("удаляет существующий шаблон")
        void deletesExisting() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingUplinkTemplate()));

            service.delete(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("id не найден -> NoSuchElementException, deleteById не вызывается")
        void deleteNotFoundThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(NoSuchElementException.class);
            verify(repository, never()).deleteById(any());
        }
    }
}
