package ru.protectinfotrans.eca.customfields.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.customfields.domain.CustomFieldRule;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleUpdateRequest;
import ru.protectinfotrans.eca.customfields.port.out.CustomFieldRuleRepositoryPort;

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
@DisplayName("CustomFieldRuleService")
class CustomFieldRuleServiceTest {

    @Mock
    private CustomFieldRuleRepositoryPort repository;

    private final CustomFieldRuleValidator validator = new CustomFieldRuleValidator();

    private CustomFieldRuleService service;

    @BeforeEach
    void setUp() {
        service = new CustomFieldRuleService(repository, validator);
    }

    private CustomFieldRule existingRule() {
        return CustomFieldRule.builder()
                .id(1L)
                .name("GATE_NUMBER")
                .description("Gate number extracted from STATUS message")
                .messageType(MessageType.DOWNLINK)
                .templateName("STATUS")
                .extractionSource(ExtractionSource.CONTENT)
                .pattern("GATE=(\\w+)")
                .active(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("создаёт правило CONTENT, активно по умолчанию")
        void createsContentRule() {
            CustomFieldRuleCreateRequest request = new CustomFieldRuleCreateRequest(
                    "GATE_NUMBER", "Gate number", MessageType.DOWNLINK, "STATUS",
                    ExtractionSource.CONTENT, "GATE=(\\w+)", null);

            when(repository.existsByName("GATE_NUMBER")).thenReturn(false);
            when(repository.save(any(CustomFieldRule.class))).thenAnswer(inv -> {
                CustomFieldRule r = inv.getArgument(0);
                r.setId(1L);
                r.setCreatedAt(LocalDateTime.now());
                r.setUpdatedAt(LocalDateTime.now());
                return r;
            });

            CustomFieldRuleResponse response = service.create(request);

            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.name()).isEqualTo("GATE_NUMBER");
            assertThat(response.active()).isTrue();

            ArgumentCaptor<CustomFieldRule> captor = ArgumentCaptor.forClass(CustomFieldRule.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isTrue();
        }

        @Test
        @DisplayName("создаёт правило METADATA с явным active=false")
        void createsMetadataRuleInactive() {
            CustomFieldRuleCreateRequest request = new CustomFieldRuleCreateRequest(
                    "PAX_COUNT", null, MessageType.DOWNLINK, null,
                    ExtractionSource.METADATA, "paxCount", false);

            when(repository.existsByName("PAX_COUNT")).thenReturn(false);
            when(repository.save(any(CustomFieldRule.class))).thenAnswer(inv -> inv.getArgument(0));

            CustomFieldRuleResponse response = service.create(request);

            assertThat(response.active()).isFalse();
            assertThat(response.extractionSource()).isEqualTo(ExtractionSource.METADATA);
        }

        @Test
        @DisplayName("дублирующееся имя -> IllegalStateException, save не вызывается")
        void duplicateNameThrows() {
            CustomFieldRuleCreateRequest request = new CustomFieldRuleCreateRequest(
                    "GATE_NUMBER", null, MessageType.DOWNLINK, null,
                    ExtractionSource.CONTENT, "GATE=(\\w+)", true);

            when(repository.existsByName("GATE_NUMBER")).thenReturn(true);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GATE_NUMBER");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("CONTENT без capturing-группы -> IllegalArgumentException (через CustomFieldRuleValidator)")
        void contentWithoutGroupThrows() {
            CustomFieldRuleCreateRequest request = new CustomFieldRuleCreateRequest(
                    "BAD_RULE", null, MessageType.DOWNLINK, null,
                    ExtractionSource.CONTENT, "GATE=\\w+", true);

            when(repository.existsByName("BAD_RULE")).thenReturn(false);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("возвращает правило по id")
        void getsById() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingRule()));

            CustomFieldRuleResponse response = service.get(1L);

            assertThat(response.name()).isEqualTo("GATE_NUMBER");
        }

        @Test
        @DisplayName("id не найден -> NoSuchElementException")
        void getByIdNotFoundThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(99L)).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Nested
    @DisplayName("list")
    class ListRules {

        @Test
        @DisplayName("возвращает страницу с маппингом PageResponse")
        void returnsPagedResponse() {
            Page<CustomFieldRule> page = new PageImpl<>(List.of(existingRule()), PageRequest.of(0, 20), 1);
            when(repository.findAll(MessageType.DOWNLINK, null, PageRequest.of(0, 20))).thenReturn(page);

            PageResponse<CustomFieldRuleResponse> result = service.list(0, 20, "DOWNLINK", null);

            assertThat(result.content()).hasSize(1);
            assertThat(result.totalElements()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("обновляет описание/тип/паттерн/активность, имя не меняется")
        void updatesMutableFields() {
            CustomFieldRule existing = existingRule();
            when(repository.findById(1L)).thenReturn(Optional.of(existing));
            when(repository.save(any(CustomFieldRule.class))).thenAnswer(inv -> inv.getArgument(0));

            CustomFieldRuleUpdateRequest request = new CustomFieldRuleUpdateRequest(
                    "Updated description", MessageType.DOWNLINK, "OOOI",
                    ExtractionSource.METADATA, "newKey", false);

            CustomFieldRuleResponse response = service.update(1L, request);

            assertThat(response.name()).isEqualTo("GATE_NUMBER"); // неизменно
            assertThat(response.description()).isEqualTo("Updated description");
            assertThat(response.templateName()).isEqualTo("OOOI");
            assertThat(response.extractionSource()).isEqualTo(ExtractionSource.METADATA);
            assertThat(response.pattern()).isEqualTo("newKey");
            assertThat(response.active()).isFalse();
        }

        @Test
        @DisplayName("id не найден -> NoSuchElementException, save не вызывается")
        void updateNotFoundThrows() {
            when(repository.findById(99L)).thenReturn(Optional.empty());

            CustomFieldRuleUpdateRequest request = new CustomFieldRuleUpdateRequest(
                    null, MessageType.DOWNLINK, null, ExtractionSource.CONTENT, "(\\w+)", true);

            assertThatThrownBy(() -> service.update(99L, request)).isInstanceOf(NoSuchElementException.class);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("некорректный паттерн при обновлении -> IllegalArgumentException, save не вызывается")
        void updateWithInvalidPatternThrows() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingRule()));

            CustomFieldRuleUpdateRequest request = new CustomFieldRuleUpdateRequest(
                    null, MessageType.DOWNLINK, null, ExtractionSource.CONTENT, "no-group-here", true);

            assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(IllegalArgumentException.class);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("удаляет существующее правило")
        void deletesExisting() {
            when(repository.findById(1L)).thenReturn(Optional.of(existingRule()));

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
