package ru.protectinfotrans.eca.sequence.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.*;
import ru.protectinfotrans.eca.sequence.port.in.SequenceManagementUseCase;
import ru.protectinfotrans.eca.user.port.out.UserLookupPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для SequenceController.
 * Проверяет делегирование к SequenceManagementUseCase и разрешение userId по Authentication.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SequenceController")
class SequenceControllerTest {

    @Mock
    private SequenceManagementUseCase sequenceUseCase;

    @Mock
    private UserLookupPort userLookupPort;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private SequenceController controller;

    @BeforeEach
    void setUp() {
        lenient().when(authentication.getName()).thenReturn("admin1");
        lenient().when(userLookupPort.findUserIdByUsername("admin1")).thenReturn(7L);
    }

    @Test
    @DisplayName("create: должен делегировать создание с резолвом userId")
    void shouldCreateSequence() {
        var request = new SequenceCreateRequest("Seq", "Desc", null, null);
        var expected = new SequenceResponse(1L, "Seq", "Desc", SequenceStatus.DRAFT,
                null, null, List.of(), null, null, 7L);
        when(sequenceUseCase.createSequence(request, 7L)).thenReturn(expected);

        SequenceResponse response = controller.create(request, authentication);

        assertThat(response).isEqualTo(expected);
        verify(sequenceUseCase).createSequence(request, 7L);
    }

    @Test
    @DisplayName("list: должен делегировать получение списка")
    void shouldListSequences() {
        var page = new PageResponse<SequenceResponse>(List.of(), 0, 0, 0, 20);
        when(sequenceUseCase.listSequences(0, 20, "DRAFT")).thenReturn(page);

        PageResponse<SequenceResponse> response = controller.list(0, 20, "DRAFT");

        assertThat(response).isEqualTo(page);
        verify(sequenceUseCase).listSequences(0, 20, "DRAFT");
    }

    @Test
    @DisplayName("get: должен делегировать получение по id")
    void shouldGetSequence() {
        var expected = new SequenceResponse(5L, "Seq", null, SequenceStatus.ACTIVE,
                null, null, List.of(), null, null, 7L);
        when(sequenceUseCase.getSequence(5L)).thenReturn(expected);

        SequenceResponse response = controller.get(5L);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    @DisplayName("update: должен делегировать обновление с резолвом userId")
    void shouldUpdateSequence() {
        var request = new SequenceUpdateRequest("New", "Desc", null, null);
        var expected = new SequenceResponse(5L, "New", "Desc", SequenceStatus.DRAFT,
                null, null, List.of(), null, null, 7L);
        when(sequenceUseCase.updateSequence(5L, request, 7L)).thenReturn(expected);

        SequenceResponse response = controller.update(5L, request, authentication);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    @DisplayName("delete: должен делегировать удаление с резолвом userId")
    void shouldDeleteSequence() {
        controller.delete(5L, authentication);

        verify(sequenceUseCase).deleteSequence(5L, 7L);
    }

    @Test
    @DisplayName("activate: должен делегировать активацию")
    void shouldActivateSequence() {
        var expected = new SequenceResponse(5L, "Seq", null, SequenceStatus.ACTIVE,
                null, null, List.of(), null, null, 7L);
        when(sequenceUseCase.activateSequence(5L, 7L)).thenReturn(expected);

        SequenceResponse response = controller.activate(5L, authentication);

        assertThat(response.status()).isEqualTo(SequenceStatus.ACTIVE);
    }

    @Test
    @DisplayName("deactivate: должен делегировать деактивацию")
    void shouldDeactivateSequence() {
        var expected = new SequenceResponse(5L, "Seq", null, SequenceStatus.INACTIVE,
                null, null, List.of(), null, null, 7L);
        when(sequenceUseCase.deactivateSequence(5L, 7L)).thenReturn(expected);

        SequenceResponse response = controller.deactivate(5L, authentication);

        assertThat(response.status()).isEqualTo(SequenceStatus.INACTIVE);
    }

    @Nested
    @DisplayName("Управление шагами")
    class StepManagement {

        @Test
        @DisplayName("addStep: должен делегировать добавление шага")
        void shouldAddStep() {
            var request = new StepCreateRequest("Step", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false, TransitionAction.ABORT, null, false);
            var expected = new StepResponse(1L, 1, "Step", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false, TransitionAction.ABORT, null, false);
            when(sequenceUseCase.addStep(5L, request, 7L)).thenReturn(expected);

            StepResponse response = controller.addStep(5L, request, authentication);

            assertThat(response).isEqualTo(expected);
        }

        @Test
        @DisplayName("updateStep: должен делегировать обновление шага")
        void shouldUpdateStep() {
            var request = new StepUpdateRequest("Step", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false, TransitionAction.ABORT, null, false);
            var expected = new StepResponse(1L, 1, "Step", StepType.ACTION, "{}", null,
                    TransitionAction.END, null, false, TransitionAction.ABORT, null, false);
            when(sequenceUseCase.updateStep(5L, 1L, request, 7L)).thenReturn(expected);

            StepResponse response = controller.updateStep(5L, 1L, request, authentication);

            assertThat(response).isEqualTo(expected);
        }

        @Test
        @DisplayName("deleteStep: должен делегировать удаление шага")
        void shouldDeleteStep() {
            controller.deleteStep(5L, 1L, authentication);

            verify(sequenceUseCase).deleteStep(5L, 1L, 7L);
        }

        @Test
        @DisplayName("reorderSteps: должен делегировать изменение порядка")
        void shouldReorderSteps() {
            List<Long> stepIds = List.of(2L, 1L);
            var expected = List.of(
                    new StepResponse(2L, 1, "B", StepType.ACTION, "{}", null,
                            TransitionAction.END, null, false, TransitionAction.ABORT, null, false));
            when(sequenceUseCase.reorderSteps(5L, stepIds, 7L)).thenReturn(expected);

            List<StepResponse> response = controller.reorderSteps(5L, stepIds, authentication);

            assertThat(response).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("resolveUserId - граничные случаи")
    class ResolveUserIdEdgeCases {

        @Test
        @DisplayName("должен вернуть null userId если Authentication равен null")
        void shouldReturnNullWhenAuthIsNull() {
            var request = new SequenceCreateRequest("Seq", null, null, null);
            var expected = new SequenceResponse(1L, "Seq", null, SequenceStatus.DRAFT,
                    null, null, List.of(), null, null, null);
            when(sequenceUseCase.createSequence(request, null)).thenReturn(expected);

            controller.create(request, null);

            verify(sequenceUseCase).createSequence(request, null);
        }

        @Test
        @DisplayName("должен вернуть null userId если пользователь не найден")
        void shouldReturnNullWhenUserNotFound() {
            when(authentication.getName()).thenReturn("ghost");
            when(userLookupPort.findUserIdByUsername("ghost")).thenReturn(null);

            var request = new SequenceCreateRequest("Seq", null, null, null);
            var expected = new SequenceResponse(1L, "Seq", null, SequenceStatus.DRAFT,
                    null, null, List.of(), null, null, null);
            when(sequenceUseCase.createSequence(request, null)).thenReturn(expected);

            controller.create(request, authentication);

            verify(sequenceUseCase).createSequence(request, null);
        }
    }
}
