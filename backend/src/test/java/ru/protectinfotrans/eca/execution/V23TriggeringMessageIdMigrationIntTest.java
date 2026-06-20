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
 * P1-7 (часть 2a, db-dev): проверяет миграцию V23 — колонка
 * {@code triggering_message_id} на {@code execution_instances} и
 * дедуп-индекс {@code idx_exec_dedup_trigger}, заложенные ADR-0002
 * (docs/adr/ADR-0002-transactional-outbox-vs-direct-call.md) как минимальная
 * схема под будущую идемпотентность {@code ExecutionService.startExecution}
 * (часть 2b — НЕ предмет этого теста и этой миграции).
 *
 * Покрывает:
 *  - Flyway применяет V23 на чистой и на заполненной демо-данными БД
 *    (resetDatabase() в BaseIntegrationTest гоняет flyway.clean()+migrate()
 *    поверх V9/V14 демо-сценариев перед каждым тестом).
 *  - Колонка triggering_message_id физически существует, nullable, BIGINT.
 *  - Индекс idx_exec_dedup_trigger создан по (sequence_id, aircraft_id,
 *    flight_number, triggering_message_id).
 *  - Entity ExecutionInstance сохраняет и читает triggeringMessageId через
 *    JPA-репозиторий (round-trip), включая NULL для инстансов без
 *    привязки к конкретному событию (обратная совместимость со старыми
 *    записями V1-V22).
 */
@DisplayName("V23 execution_instances: triggering_message_id колонка + дедуп-индекс")
class V23TriggeringMessageIdMigrationIntTest extends BaseIntegrationTest {

    @Autowired
    private ExecutionRepositoryPort executionRepository;

    @Test
    @DisplayName("information_schema содержит nullable-колонку triggering_message_id типа bigint")
    void migrationAddsTriggeringMessageIdColumn() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_name = 'execution_instances'
                  AND column_name = 'triggering_message_id'
                """);

        assertThat(columns).hasSize(1);
        Map<String, Object> column = columns.get(0);
        assertThat(column.get("data_type")).isEqualTo("bigint");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    @DisplayName("дедуп-индекс idx_exec_dedup_trigger создан по (sequence_id, aircraft_id, flight_number, triggering_message_id)")
    void migrationCreatesDedupIndex() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'execution_instances'
                  AND indexname = 'idx_exec_dedup_trigger'
                """);

        assertThat(indexes).hasSize(1);
        String indexDef = (String) indexes.get(0).get("indexdef");
        assertThat(indexDef)
                .contains("sequence_id")
                .contains("aircraft_id")
                .contains("flight_number")
                .contains("triggering_message_id");
    }

    @Test
    @DisplayName("save() сохраняет и читает обратно triggeringMessageId через JPA-репозиторий")
    void savedInstancePersistsTriggeringMessageId() {
        ExecutionInstance instance = ExecutionInstance.builder()
                .sequenceId(1L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .triggeringMessageId(42L)
                .build();

        ExecutionInstance saved = executionRepository.save(instance);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTriggeringMessageId()).isEqualTo(42L);

        ExecutionInstance reloaded = executionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTriggeringMessageId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("инстанс без привязки к событию (старые записи, ручной старт) сохраняет triggeringMessageId = NULL")
    void savedInstanceAllowsNullTriggeringMessageId() {
        ExecutionInstance instance = ExecutionInstance.builder()
                .sequenceId(1L)
                .aircraftId("VP-BQR")
                .flightNumber("SU1234")
                .status(ExecutionStatus.RUNNING)
                .currentStepIndex(1)
                .build();

        ExecutionInstance saved = executionRepository.save(instance);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTriggeringMessageId()).isNull();

        ExecutionInstance reloaded = executionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTriggeringMessageId()).isNull();
    }
}
