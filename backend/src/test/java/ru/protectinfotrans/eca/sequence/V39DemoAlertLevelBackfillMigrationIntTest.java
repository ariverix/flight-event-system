package ru.protectinfotrans.eca.sequence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.sequence.domain.AlertLevel;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет миграцию V39 — бэкфилл demo-данных V11/V14/V15 (уже применённые, alertLevel=INFO/
 * WARNING), которые не входят в enum {@link AlertLevel} и валили RAISE_CONDITION-шаги
 * IllegalArgumentException'ом при выполнении (ActionStepRule.execute → StepResult.FAILURE).
 * Найдено при ревью фикса legacy StepForm.tsx (frontend alertLevel INFO/WARNING/CRITICAL vs
 * канонический NO/LOW/MEDIUM/HIGH/CRITICAL, CLAUDE.md).
 */
@DisplayName("V39 бэкфилл alertLevel demo-данных: значения стали каноническими (AlertLevel enum)")
class V39DemoAlertLevelBackfillMigrationIntTest extends BaseIntegrationTest {

    private static final List<String> CANONICAL_LEVELS =
            Arrays.stream(AlertLevel.values()).map(Enum::name).toList();

    @Test
    @DisplayName("ни один RAISE_CONDITION/CLOSE_CONDITION-шаг в БД не содержит нестандартный alertLevel")
    void noStepHasNonCanonicalAlertLevel() {
        List<String> alertLevels = jdbcTemplate.queryForList("""
                SELECT config ->> 'alertLevel' AS alert_level
                FROM steps
                WHERE config ->> 'alertLevel' IS NOT NULL
                """, String.class);

        assertThat(alertLevels).isNotEmpty();
        assertThat(alertLevels).allSatisfy(level ->
                assertThat(CANONICAL_LEVELS).as("alertLevel '%s' должен входить в canonical AlertLevel", level)
                        .contains(level));
    }

    @Test
    @DisplayName("известные demo-условия получили ожидаемый канонический уровень после бэкфилла")
    void knownDemoConditionsMappedToExpectedCanonicalLevel() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT config ->> 'conditionName' AS condition_name, config ->> 'alertLevel' AS alert_level
                FROM steps
                WHERE config ->> 'actionType' = 'RAISE_CONDITION'
                """);

        Map<String, String> levelByCondition = rows.stream()
                .collect(Collectors.toMap(
                        r -> (String) r.get("condition_name"),
                        r -> (String) r.get("alert_level")));

        // старый INFO → LOW
        assertThat(levelByCondition.get("WEATHER_ADVISORY_SENT")).isEqualTo("LOW");
        assertThat(levelByCondition.get("DEMO_MODE")).isEqualTo("LOW");
        assertThat(levelByCondition.get("DEMO_COMPLETE")).isEqualTo("LOW");
        // старый WARNING → MEDIUM
        // (DEMO_NO_ACK из V14 не проверяем — V15 удаляет и полностью пересобирает шаги
        // этой demo-последовательности, этого conditionName в текущих данных больше нет)
        assertThat(levelByCondition.get("NO_LANDING_CONTACT")).isEqualTo("MEDIUM");
        assertThat(levelByCondition.get("FLIGHT_DELAYED")).isEqualTo("MEDIUM");
        // уже валидный CRITICAL — не тронут
        assertThat(levelByCondition.get("PREFLIGHT_TIMEOUT")).isEqualTo("CRITICAL");
    }
}
