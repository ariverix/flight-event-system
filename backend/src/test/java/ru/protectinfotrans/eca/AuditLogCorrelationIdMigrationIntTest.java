package ru.protectinfotrans.eca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет каркас P0-4 (часть 2): миграция V20 добавляет колонку
 * {@code audit_log.correlation_id}, и она доступна через JPA-entity {@link AuditLog},
 * связывая записи аудита со структурными логами по значению
 * {@link CorrelationContext#CORRELATION_ID}.
 */
@DisplayName("audit_log.correlation_id — каркас миграции V20")
class AuditLogCorrelationIdMigrationIntTest extends BaseIntegrationTest {

    @Autowired
    private AuditLogQueryRepository auditLogQueryRepository;

    @Test
    @DisplayName("колонка correlation_id существует в схеме после миграции V20")
    void correlationIdColumnExistsInSchema() {
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_name = 'audit_log'
                  AND column_name = 'correlation_id'
                  AND data_type = 'character varying'
                  AND character_maximum_length = 64
                """, Integer.class);

        assertThat(columnCount).isEqualTo(1);
    }

    @Test
    @DisplayName("AuditLog сохраняет и читает correlationId через JPA-маппинг")
    void savesAndReadsCorrelationIdViaJpa() {
        String correlationId = UUID.randomUUID().toString();

        AuditLog auditLog = AuditLog.builder()
                .userId(1L)
                .action("TEST_ACTION")
                .entityType("TEST_ENTITY")
                .entityId(42L)
                .correlationId(correlationId)
                .build();

        AuditLog saved = auditLogQueryRepository.save(auditLog);

        AuditLog reloaded = auditLogQueryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCorrelationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("correlationId остаётся null для записей без HTTP-контекста (системные действия)")
    void correlationIdIsNullableForSystemActions() {
        AuditLog auditLog = AuditLog.builder()
                .action("SYSTEM_ACTION")
                .entityType("SYSTEM")
                .build();

        AuditLog saved = auditLogQueryRepository.save(auditLog);

        AuditLog reloaded = auditLogQueryRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("AuditLogResponse.fromEntity прокидывает correlationId в DTO")
    void responseDtoExposesCorrelationId() {
        String correlationId = UUID.randomUUID().toString();
        AuditLog auditLog = AuditLog.builder()
                .action("TEST_ACTION")
                .entityType("TEST_ENTITY")
                .correlationId(correlationId)
                .build();

        AuditLogResponse response = AuditLogResponse.fromEntity(auditLog);

        assertThat(response.getCorrelationId()).isEqualTo(correlationId);
    }
}
