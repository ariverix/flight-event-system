package ru.protectinfotrans.eca.conditions.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.protectinfotrans.eca.conditions.dto.RaisedConditionResponse;
import ru.protectinfotrans.eca.conditions.port.in.ConditionQueryUseCase;

import java.util.List;

/**
 * Операторский обзор активных custom conditions (P3-3) — read-only, RBAC (см.
 * {@code SecurityConfig}, правило {@code /api/v1/conditions/**} добавлено ДО catch-all
 * {@code anyRequest().permitAll()}, тот же принцип, что у {@code /api/v1/custom-field-rules/**},
 * P3-2). Raise/close НЕ выставлены как отдельные REST-операции — это исключительно ACTION-шаг
 * движка (RAISE_CONDITION/CLOSE_CONDITION, см. {@code ActionStepRule}), нет сценария "оператор
 * вручную поднимает условие через UI" в паритетной спецификации SITA для этой задачи.
 */
@Tag(name = "Conditions", description = "Активные custom conditions рейсов (P3-3)")
@RestController
@RequestMapping("/api/v1/conditions")
@RequiredArgsConstructor
public class ConditionController {

    private final ConditionQueryUseCase conditionQueryUseCase;

    @Operation(summary = "Список активных условий", description = "Все активные (не закрытые) условия по всем бортам/рейсам.")
    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_CONDITIONS')")
    public List<RaisedConditionResponse> listActive() {
        return conditionQueryUseCase.listAllActive().stream()
                .map(RaisedConditionResponse::from)
                .toList();
    }
}
