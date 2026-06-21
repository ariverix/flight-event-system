package ru.protectinfotrans.eca.integration.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.integration.application.DeadLetterQueueService;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterSource;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.dto.DeadLetterMessageResponse;
import ru.protectinfotrans.eca.integration.dto.DeadLetterReprocessResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-6: unit-тесты {@link DeadLetterController} — REST-уровень (список/детали/reprocess/discard).
 * RBAC ({@code hasAnyRole('OPERATOR','ADMIN')}, не-permitAll) проверяется на уровне
 * {@code SecurityConfig} интеграционным тестом с реальным HTTP-стеком/JWT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadLetterController")
class DeadLetterControllerTest {

    @Mock
    private DeadLetterQueueService deadLetterQueueService;

    private DeadLetterController controller;

    @BeforeEach
    void setUp() {
        controller = new DeadLetterController(deadLetterQueueService);
    }

    private DeadLetterMessage sampleEntry(Long id, DeadLetterStatus status) {
        return DeadLetterMessage.builder()
                .id(id)
                .source(DeadLetterSource.RAW_GATEWAY)
                .format("AFTN")
                .rawPayload("GARBAGE")
                .reason("MessageParsingException: broken")
                .status(status)
                .attempts(0)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("list: без статуса -> делегирует в service.list(null, pageable), маппит в PageResponse")
    void listWithoutStatusFilter() {
        DeadLetterMessage entry = sampleEntry(1L, DeadLetterStatus.NEW);
        Page<DeadLetterMessage> page = new org.springframework.data.domain.PageImpl<>(
                List.of(entry), PageRequest.of(0, 20), 1);
        when(deadLetterQueueService.list(isNull(), any())).thenReturn(page);

        ResponseEntity<PageResponse<DeadLetterMessageResponse>> response = controller.list(0, 20, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).hasSize(1);
        assertThat(response.getBody().content().get(0).id()).isEqualTo(1L);
        verify(deadLetterQueueService).list(eq(null), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("list: со статусом NEW -> делегирует в service.list(NEW, pageable)")
    void listWithStatusFilter() {
        when(deadLetterQueueService.list(eq(DeadLetterStatus.NEW), any())).thenReturn(Page.empty());

        controller.list(1, 10, DeadLetterStatus.NEW);

        verify(deadLetterQueueService).list(eq(DeadLetterStatus.NEW), eq(PageRequest.of(1, 10)));
    }

    @Test
    @DisplayName("get: возвращает детали записи через getOrThrow")
    void getReturnsEntryDetails() {
        DeadLetterMessage entry = sampleEntry(5L, DeadLetterStatus.NEW);
        when(deadLetterQueueService.getOrThrow(5L)).thenReturn(entry);

        ResponseEntity<DeadLetterMessageResponse> response = controller.get(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(5L);
        assertThat(response.getBody().rawPayload()).isEqualTo("GARBAGE");
    }

    @Test
    @DisplayName("reprocess: успех -> success=true, статус из записи ПОСЛЕ попытки (REPROCESSED)")
    void reprocessSuccessReturnsUpdatedStatus() {
        when(deadLetterQueueService.reprocess(1L)).thenReturn(true);
        DeadLetterMessage afterAttempt = sampleEntry(1L, DeadLetterStatus.REPROCESSED);
        when(deadLetterQueueService.getOrThrow(1L)).thenReturn(afterAttempt);

        ResponseEntity<DeadLetterReprocessResponse> response = controller.reprocess(1L);

        assertThat(response.getBody().dlqId()).isEqualTo(1L);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().status()).isEqualTo("REPROCESSED");
    }

    @Test
    @DisplayName("reprocess: повторный сбой -> success=false, статус остаётся NEW")
    void reprocessFailureReturnsNewStatus() {
        when(deadLetterQueueService.reprocess(2L)).thenReturn(false);
        DeadLetterMessage afterAttempt = sampleEntry(2L, DeadLetterStatus.NEW);
        when(deadLetterQueueService.getOrThrow(2L)).thenReturn(afterAttempt);

        ResponseEntity<DeadLetterReprocessResponse> response = controller.reprocess(2L);

        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().status()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("discard: делегирует в service.discard и возвращает 200")
    void discardDelegatesToService() {
        ResponseEntity<Void> response = controller.discard(3L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(deadLetterQueueService).discard(3L);
    }
}
