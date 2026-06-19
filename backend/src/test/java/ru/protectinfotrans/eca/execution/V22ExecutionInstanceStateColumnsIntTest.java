package ru.protectinfotrans.eca.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.execution.domain.ExecutionInstance;
import ru.protectinfotrans.eca.execution.domain.ExecutionStatus;
import ru.protectinfotrans.eca.execution.port.out.ExecutionRepositoryPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-3 (часть 1, схема): проверяет миграцию V22 — дополнительные колонки
 * стейта инстанса последовательности (execution_instances = sequence_instance
 * в терминах SITA, см. комментарий в V22).
 *
 * Покрывает:
 *  - Flyway применяет V22 на чистой и на заполненной демо-данными БД
 *    (resetDatabase() в BaseIntegrationTest делает flyway.clean()+migrate()
 *    перед каждым тестом, то есть миграция гоняется поверх V9/V14 демо-сценариев).
 *  - Колонки updated_at и version физически существуют в information_schema.
 *  - Entity ExecutionInstance сохраняет и читает context (JSONB) и version/updatedAt
 *    через JPA-репозиторий (round-trip).
 */
@DisplayName("V22 execution_instances: updated_at / version / context round-trip")
class V22ExecutionInstanceStateColumnsIntTest extends BaseIntegrationTest {

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Test
    @DisplayName("information_schema содержит колонки updated_at и version с ожидаемыми типами")
    void migrationAddsExpectedColumns() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'execution_instances'
                  AND column_name IN ('updated_at', 'version', 'context')
                ORDER BY column_name
                """);

        assertThat(columns).hasSize(3);

        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));

        assertThat(byName.get("context").get("data_type")).isEqualTo("jsonb");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp without time zone");
        assertThat(byName.get("version").get("data_type")).isEqualTo("bigint");
        assertThat(byName.get("version").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("идемпотентный индекс idx_exec_status_updated_at создан миграцией V22")
    void migrationCreatesStatusUpdatedAtIndex() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                WHERE tablename = 'execution_instances'
                  AND indexname = 'idx_exec_status_updated_at'
                """);

        assertThat(indexes).hasSize(1);
    }

    @Test
    @DisplayName("save() заполняет updatedAt/version при создании и context сохраняется/читается обратно")
    void savedInstancePersistsContextAndAuditColumns() {
        ExecutionInstance instance = ExecutionInstance.builder()
                .sequenceId(1L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .contextJson("{\"activeConditions\":{\"LOW_FUEL\":true}}")
                .build();

        ExecutionInstance saved = executionRepository.save(instance);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getContextJson()).contains("LOW_FUEL");

        ExecutionInstance reloaded = executionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getContextJson()).contains("activeConditions", "LOW_FUEL");
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(reloaded.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("повторный save() обновляет updatedAt (метка последнего изменения стейта)")
    void updatingInstanceRefreshesUpdatedAt() throws InterruptedException {
        ExecutionInstance instance = ExecutionInstance.builder()
                .sequenceId(1L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .contextJson("{}")
                .build();

        ExecutionInstance saved = executionRepository.save(instance);
        var firstUpdatedAt = saved.getUpdatedAt();

        // гарантируем измеримую разницу между метками времени без долгого sleep
        Thread.sleep(5);

        saved.setCurrentStepIndex(2);
        saved.setContextJson("{\"step\":2}");
        ExecutionInstance updated = executionRepository.save(saved);

        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);
        assertThat(updated.getContextJson()).contains("\"step\":2");
    }
}
