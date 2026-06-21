package ru.protectinfotrans.eca.integration.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.integration.application.DeadLetterQueueService;
import ru.protectinfotrans.eca.integration.domain.DeadLetterMessage;
import ru.protectinfotrans.eca.integration.domain.DeadLetterStatus;
import ru.protectinfotrans.eca.integration.dto.DeadLetterMessageResponse;
import ru.protectinfotrans.eca.integration.dto.DeadLetterReprocessResponse;

/**
 * P2-6: административный эндпоинт DLQ (Dead Letter Queue) для оператора — список сбойных
 * входящих сообщений + ручной reprocess/discard.
 *
 * <p><b>RBAC, НЕ permitAll (отличие от {@code RawMessageController}/{@code MessageController}):</b>
 * это не приём от внешней ACARS-машины (которая физически не умеет в JWT, см. CLAUDE.md
 * "Жёсткие правила" — открытый только сам ingestion-путь), а АДМИНСКАЯ операция оператора над
 * уже принятыми (и сбойными) сообщениями — защищена так же, как {@code /api/v1/executions/**}
 * и {@code /api/v1/sequences/**} ({@code hasAnyRole('OPERATOR', 'ADMIN')} в
 * {@code SecurityConfig}, методно через {@code @PreAuthorize} здесь — тот же стиль, что
 * {@code ExecutionController}).
 */
@Tag(name = "DeadLetterQueue", description = "P2-6: сбойные входящие сообщения (DLQ) + ручной reprocess (OPERATOR/ADMIN)")
@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
public class DeadLetterController {

    private final DeadLetterQueueService deadLetterQueueService;

    @Operation(summary = "Список DLQ-записей", description = "Сбойные входящие сообщения с пагинацией, опционально по статусу (NEW/REPROCESSED/DISCARDED)")
    @GetMapping
    public ResponseEntity<PageResponse<DeadLetterMessageResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) DeadLetterStatus status
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<DeadLetterMessage> result = deadLetterQueueService.list(status, pageable);

        PageResponse<DeadLetterMessageResponse> response = new PageResponse<>(
                result.getContent().stream().map(DeadLetterMessageResponse::fromEntity).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize()
        );
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Детали DLQ-записи", description = "Сырое тело сообщения, причина сбоя, стектрейс, история попыток")
    @GetMapping("/{id}")
    public ResponseEntity<DeadLetterMessageResponse> get(@PathVariable Long id) {
        DeadLetterMessage entry = deadLetterQueueService.getOrThrow(id);
        return ResponseEntity.ok(DeadLetterMessageResponse.fromEntity(entry));
    }

    @Operation(summary = "Ручной reprocess DLQ-записи",
               description = "Повторно прогоняет сырое сообщение через парсинг + приём. При успехе статус -> REPROCESSED, "
                       + "при повторном сбое запись остаётся NEW с обновлённой причиной/счётчиком попыток.")
    @PostMapping("/{id}/reprocess")
    public ResponseEntity<DeadLetterReprocessResponse> reprocess(@PathVariable Long id) {
        boolean success = deadLetterQueueService.reprocess(id);
        DeadLetterMessage afterAttempt = deadLetterQueueService.getOrThrow(id);
        return ResponseEntity.ok(new DeadLetterReprocessResponse(id, success, afterAttempt.getStatus().name()));
    }

    @Operation(summary = "Отбросить DLQ-запись", description = "Оператор решил, что сообщение не нужно — дальнейший reprocess не предполагается")
    @PostMapping("/{id}/discard")
    public ResponseEntity<Void> discard(@PathVariable Long id) {
        deadLetterQueueService.discard(id);
        return ResponseEntity.ok().build();
    }
}
