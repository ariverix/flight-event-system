package ru.protectinfotrans.eca.eventprocessor.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.protectinfotrans.eca.eventprocessor.application.MessageQueryService;
import ru.protectinfotrans.eca.eventprocessor.dto.AircraftSummaryResponse;

/**
 * Фаза 5 (прогон апгрейда): список бортов для UI aircraft-bindings (закрытие TODO P7-3 —
 * привязка последовательности к бортам по tail number / AN).
 *
 * <p>Борта в системе не имеют отдельного реестра/типа — эндпоинт отдаёт различные наблюдавшиеся
 * {@code aircraft_id} (tail numbers) с метаданными (последний контакт, объём сообщений, число
 * рейсов), проекция над журналом {@code messages}. Именно tail number нужен UI для привязки.
 *
 * <p>За RBAC {@code VIEW_SEQUENCES} (path-level в SecurityConfig + method-level {@code @PreAuthorize})
 * — тот же уровень доступа, что чтение последовательностей, для которых борта и выбираются.
 */
@Tag(name = "Aircraft", description = "Фаза 5: список бортов (tail numbers) для привязки последовательностей")
@RestController
@RequestMapping("/api/v1/aircraft")
@RequiredArgsConstructor
public class AircraftController {

    private static final int MAX_PAGE_SIZE = 200;

    private final MessageQueryService messageQueryService;

    @Operation(summary = "Список бортов",
            description = "Различные tail numbers (AN) с метаданными последнего контакта и объёма наблюдений; "
                    + "опциональный подстрочный поиск по регистрационному номеру. Пагинация; сортировка — "
                    + "последний активный борт сверху. Типа ВС в системе нет (нет данных).")
    @ApiResponse(responseCode = "200", description = "Страница бортов")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_SEQUENCES')")
    public ResponseEntity<Page<AircraftSummaryResponse>> listAircraft(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        // сортировка фиксирована в JPQL (последний контакт сверху) — Pageable без Sort
        Page<AircraftSummaryResponse> result =
                messageQueryService.findAircraft(search, PageRequest.of(safePage, safeSize));
        return ResponseEntity.ok(result);
    }
}
