package ru.protectinfotrans.eca.sequence.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.sequence.domain.*;
import ru.protectinfotrans.eca.sequence.dto.*;
import ru.protectinfotrans.eca.sequence.event.SequenceActivatedEvent;
import ru.protectinfotrans.eca.sequence.event.SequenceDeactivatedEvent;
import ru.protectinfotrans.eca.sequence.port.out.AuditLogPort;
import ru.protectinfotrans.eca.sequence.port.out.SequenceRepositoryPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты сервиса управления последовательностями.
 * Покрывает все бизнес-правила из UC-01..UC-04.
 *
 * См. диплом: Глава 3 (Тестирование)
 */
@ExtendWith(MockitoExtension.class)
class SequenceServiceTest {

    @Mock
    private SequenceRepositoryPort sequenceRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @Mock
    private SequenceValidator validator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SequenceService service;

    private Sequence draftSequence;

    @BeforeEach
    void setUp() {
        draftSequence = Sequence.builder()
                .id(1L)
                .name("Test Sequence")
                .description("Test")
                .status(SequenceStatus.DRAFT)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(1L)
                .build();
        draftSequence.setSteps(new ArrayList<>());
    }

    @Nested
    @DisplayName("UC-01: Создание последовательности")
    class CreateSequence {

        @Test
        @DisplayName("Создаёт последовательность в статусе DRAFT")
        void shouldCreateSequenceInDraftStatus() {
            var request = new SequenceCreateRequest("New Seq", "Desc", null, null);
            when(sequenceRepository.save(any(Sequence.class))).thenAnswer(inv -> {
                Sequence s = inv.getArgument(0);
                s.setId(1L);
                return s;
            });

            SequenceResponse response = service.createSequence(request, 1L);

            assertThat(response.name()).isEqualTo("New Seq");
            assertThat(response.status()).isEqualTo(SequenceStatus.DRAFT);
            verify(auditLogPort).save(any(AuditLog.class));
        }
    }

    @Nested
    @DisplayName("UC-01: Обновление последовательности")
    class UpdateSequence {

        @Test
        @DisplayName("Обновляет метаданные DRAFT последовательности")
        void shouldUpdateDraftSequence() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new SequenceUpdateRequest("Updated", "New desc", null, null);
            SequenceResponse response = service.updateSequence(1L, request, 1L);

            assertThat(response.name()).isEqualTo("Updated");
            assertThat(response.description()).isEqualTo("New desc");
        }

        @Test
        @DisplayName("Запрещает обновление не-DRAFT последовательности")
        void shouldRejectUpdateOfActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new SequenceUpdateRequest("X", "Y", null, null);
            assertThatThrownBy(() -> service.updateSequence(1L, request, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT");
        }
    }

    @Nested
    @DisplayName("UC-01: Удаление последовательности")
    class DeleteSequence {

        @Test
        @DisplayName("Удаляет DRAFT последовательность")
        void shouldDeleteDraftSequence() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            service.deleteSequence(1L, 1L);

            verify(sequenceRepository).deleteById(1L);
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Запрещает удаление ACTIVE последовательности")
        void shouldRejectDeleteOfActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.deleteSequence(1L, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DRAFT");
        }
    }

    @Nested
    @DisplayName("UC-04: Активация последовательности")
    class ActivateSequence {

        @Test
        @DisplayName("Активирует DRAFT последовательность после валидации")
        void shouldActivateDraftSequence() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(validator.validate(draftSequence)).thenReturn(List.of());
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SequenceResponse response = service.activateSequence(1L, 1L);

            assertThat(response.status()).isEqualTo(SequenceStatus.ACTIVE);
            verify(eventPublisher).publishEvent(any(SequenceActivatedEvent.class));
        }

        @Test
        @DisplayName("Активирует INACTIVE последовательность повторно")
        void shouldReactivateInactiveSequence() {
            draftSequence.setStatus(SequenceStatus.INACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(validator.validate(draftSequence)).thenReturn(List.of());
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SequenceResponse response = service.activateSequence(1L, 1L);

            assertThat(response.status()).isEqualTo(SequenceStatus.ACTIVE);
        }

        @Test
        @DisplayName("Отклоняет активацию при ошибках валидации")
        void shouldRejectActivationWithValidationErrors() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(validator.validate(draftSequence))
                    .thenReturn(List.of("Минимум 1 шаг"));

            assertThatThrownBy(() -> service.activateSequence(1L, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Минимум 1 шаг");
        }

        @Test
        @DisplayName("Запрещает повторную активацию ACTIVE последовательности")
        void shouldRejectActivationOfAlreadyActive() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.activateSequence(1L, 1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("UC-04: Деактивация последовательности")
    class DeactivateSequence {

        @Test
        @DisplayName("Деактивирует ACTIVE последовательность")
        void shouldDeactivateActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SequenceResponse response = service.deactivateSequence(1L, 1L);

            assertThat(response.status()).isEqualTo(SequenceStatus.INACTIVE);
            verify(eventPublisher).publishEvent(any(SequenceDeactivatedEvent.class));
        }

        @Test
        @DisplayName("Запрещает деактивацию DRAFT последовательности")
        void shouldRejectDeactivationOfDraft() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.deactivateSequence(1L, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ACTIVE");
        }
    }

    @Nested
    @DisplayName("UC-02: Управление шагами")
    class StepManagement {

        @Test
        @DisplayName("Добавляет шаг с автоинкрементом orderIndex")
        void shouldAddStepWithAutoIndex() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> {
                Sequence s = inv.getArgument(0);
                s.getSteps().forEach(step -> {
                    if (step.getId() == null) step.setId(10L);
                });
                return s;
            });

            var request = new StepCreateRequest(
                    "Step 1", StepType.ACTION, "{\"actionType\":\"SEND_UPLINK\"}",
                    null, TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false);

            StepResponse response = service.addStep(1L, request, 1L);

            assertThat(response.orderIndex()).isEqualTo(1);
            assertThat(response.stepType()).isEqualTo(StepType.ACTION);
        }

        @Test
        @DisplayName("Запрещает добавление шага в не-DRAFT последовательность")
        void shouldRejectAddStepToActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepCreateRequest(
                    "Step", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.addStep(1L, request, 1L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Отклоняет WAIT-шаг без таймаута")
        void shouldRejectWaitStepWithoutTimeout() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepCreateRequest(
                    "Wait", StepType.WAIT, "{}", null,
                    TransitionAction.CONTINUE, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.addStep(1L, request, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("таймаут");
        }

        @Test
        @DisplayName("Отклоняет GOTO без указания целевого шага")
        void shouldRejectGotoWithoutTarget() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepCreateRequest(
                    "Step", StepType.ACTION, "{}",
                    null, TransitionAction.GOTO, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.addStep(1L, request, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GOTO");
        }
    }

    @Nested
    @DisplayName("UC-02: Обновление шага")
    class UpdateStepTests {

        private Step existingStep;

        @BeforeEach
        void setUp() {
            existingStep = Step.builder()
                    .id(10L)
                    .sequence(draftSequence)
                    .orderIndex(1)
                    .name("Old name")
                    .stepType(StepType.ACTION)
                    .configJson("{}")
                    .onSuccessAction(TransitionAction.CONTINUE)
                    .onFailureAction(TransitionAction.ABORT)
                    .build();
            draftSequence.getSteps().add(existingStep);
        }

        @Test
        @DisplayName("Обновляет существующий шаг")
        void shouldUpdateExistingStep() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new StepUpdateRequest(
                    "New name", StepType.EVALUATE, "{\"x\":1}", null,
                    TransitionAction.END, null, true,
                    TransitionAction.GOTO, 1, true);

            StepResponse response = service.updateStep(1L, 10L, request, 1L);

            assertThat(response.name()).isEqualTo("New name");
            assertThat(response.stepType()).isEqualTo(StepType.EVALUATE);
            assertThat(response.onSuccessAction()).isEqualTo(TransitionAction.END);
            assertThat(response.onFailureAction()).isEqualTo(TransitionAction.GOTO);
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Бросает исключение если шаг не найден")
        void shouldThrowWhenStepNotFound() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepUpdateRequest(
                    "X", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.updateStep(1L, 999L, request, 1L))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("Запрещает обновление шага не-DRAFT последовательности")
        void shouldRejectUpdateStepOfActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepUpdateRequest(
                    "X", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.updateStep(1L, 10L, request, 1L))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Отклоняет обновление WAIT-шага без таймаута")
        void shouldRejectWaitStepUpdateWithoutTimeout() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            var request = new StepUpdateRequest(
                    "Wait", StepType.WAIT, "{}", null,
                    TransitionAction.CONTINUE, null, false,
                    TransitionAction.ABORT, null, false);

            assertThatThrownBy(() -> service.updateStep(1L, 10L, request, 1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("таймаут");
        }
    }

    @Nested
    @DisplayName("UC-02: Удаление шага")
    class DeleteStepTests {

        @Test
        @DisplayName("Удаляет шаг и переиндексирует оставшиеся")
        void shouldDeleteStepAndReindex() {
            Step step1 = Step.builder().id(10L).sequence(draftSequence).orderIndex(1)
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.CONTINUE).onFailureAction(TransitionAction.ABORT).build();
            Step step2 = Step.builder().id(20L).sequence(draftSequence).orderIndex(2)
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.END).onFailureAction(TransitionAction.ABORT).build();
            draftSequence.getSteps().add(step1);
            draftSequence.getSteps().add(step2);

            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.deleteStep(1L, 10L, 1L);

            assertThat(draftSequence.getSteps()).hasSize(1);
            assertThat(draftSequence.getSteps().get(0).getOrderIndex()).isEqualTo(1);
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Бросает исключение если шаг для удаления не найден")
        void shouldThrowWhenStepToDeleteNotFound() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.deleteStep(1L, 999L, 1L))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("Запрещает удаление шага из не-DRAFT последовательности")
        void shouldRejectDeleteStepFromActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.deleteStep(1L, 10L, 1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("UC-02: Изменение порядка шагов")
    class ReorderStepsTests {

        private Step step1;
        private Step step2;

        @BeforeEach
        void setUp() {
            step1 = Step.builder().id(10L).sequence(draftSequence).orderIndex(1)
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.CONTINUE).onFailureAction(TransitionAction.ABORT).build();
            step2 = Step.builder().id(20L).sequence(draftSequence).orderIndex(2)
                    .stepType(StepType.ACTION).configJson("{}")
                    .onSuccessAction(TransitionAction.END).onFailureAction(TransitionAction.ABORT).build();
            draftSequence.getSteps().add(step1);
            draftSequence.getSteps().add(step2);
        }

        @Test
        @DisplayName("Переставляет шаги в новом порядке")
        void shouldReorderSteps() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));
            when(sequenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            List<StepResponse> response = service.reorderSteps(1L, List.of(20L, 10L), 1L);

            assertThat(step2.getOrderIndex()).isEqualTo(1);
            assertThat(step1.getOrderIndex()).isEqualTo(2);
            assertThat(response).extracting(StepResponse::id).containsExactly(20L, 10L);
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Бросает исключение если количество id не совпадает")
        void shouldThrowWhenCountMismatch() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.reorderSteps(1L, List.of(10L), 1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Бросает исключение если id шага не найден")
        void shouldThrowWhenStepIdNotFound() {
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.reorderSteps(1L, List.of(10L, 999L), 1L))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("Запрещает изменение порядка в не-DRAFT последовательности")
        void shouldRejectReorderOfActiveSequence() {
            draftSequence.setStatus(SequenceStatus.ACTIVE);
            when(sequenceRepository.findById(1L)).thenReturn(Optional.of(draftSequence));

            assertThatThrownBy(() -> service.reorderSteps(1L, List.of(10L, 20L), 1L))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Пагинация и получение")
    class QueryOperations {

        @Test
        @DisplayName("Возвращает пагинированный список")
        void shouldReturnPaginatedList() {
            Page<Sequence> page = new PageImpl<>(List.of(draftSequence));
            when(sequenceRepository.findAll(any(Pageable.class))).thenReturn(page);

            PageResponse<SequenceResponse> response = service.listSequences(0, 20, null);

            assertThat(response.content()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("Фильтрует по статусу")
        void shouldFilterByStatus() {
            Page<Sequence> page = new PageImpl<>(List.of(draftSequence));
            when(sequenceRepository.findByStatus(eq(SequenceStatus.DRAFT), any(Pageable.class)))
                    .thenReturn(page);

            PageResponse<SequenceResponse> response = service.listSequences(0, 20, "DRAFT");

            assertThat(response.content()).hasSize(1);
            verify(sequenceRepository).findByStatus(eq(SequenceStatus.DRAFT), any());
        }

        @Test
        @DisplayName("Бросает исключение если последовательность не найдена")
        void shouldThrowIfNotFound() {
            when(sequenceRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getSequence(999L))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }
}
