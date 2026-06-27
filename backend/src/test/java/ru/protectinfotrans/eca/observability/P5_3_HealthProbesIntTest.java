package ru.protectinfotrans.eca.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-3: Kubernetes-style health probes (liveness / readiness / startup) через Spring Boot Actuator.
 *
 * <p>Проверяет:
 * <ul>
 *   <li>/actuator/health/liveness  — доступен без JWT, всегда UP пока JVM жива;</li>
 *   <li>/actuator/health/readiness — доступен без JWT, зависит от БД + integrationChannels;</li>
 *   <li>/actuator/health/startup   — доступен без JWT (k8s startup probe);</li>
 *   <li>liveness НЕ деградирует при проблемах с каналом/DLQ (k8s не рестартит под зря);</li>
 *   <li>readiness → DOWN (HTTP 503) при OPEN circuit breaker на исходящем канале;</li>
 *   <li>readiness → OUT_OF_SERVICE (HTTP 503) при превышении порога DLQ;</li>
 *   <li>/actuator/health (полный) → 401 для анонима (за RBAC SYSTEM_ADMIN).</li>
 * </ul>
 *
 * <b>TDD (P5-3, CLAUDE.md):</b> тест написан первым — реализация следует за ним.
 */
@AutoConfigureObservability
@DisplayName("P5-3: Kubernetes health probes liveness / readiness / startup")
class P5_3_HealthProbesIntTest extends BaseIntegrationTest {

    // ------------------------------------------------------------------ //
    // 1. Доступность без аутентификации (k8s-пробам JWT недоступен)       //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("liveness без JWT → HTTP 200 + status UP")
    void livenessUpWithoutAuth() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/liveness", String.class);

        assertThat(resp.getStatusCode().value())
                .as("liveness probe: HTTP 200 без аутентификации")
                .isEqualTo(200);
        assertThat(resp.getBody())
                .as("liveness probe: тело содержит status UP")
                .contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("readiness без JWT → HTTP 200 + status UP в нормальном состоянии")
    void readinessUpWithoutAuthWhenChannelsOk() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);

        assertThat(resp.getStatusCode().value())
                .as("readiness probe: HTTP 200 без аутентификации")
                .isEqualTo(200);
        assertThat(resp.getBody())
                .as("readiness probe: тело содержит status UP")
                .contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("startup без JWT → HTTP 200 (k8s startup probe)")
    void startupUpWithoutAuth() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/startup", String.class);

        assertThat(resp.getStatusCode().value())
                .as("startup probe: HTTP 200 без аутентификации")
                .isEqualTo(200);
        assertThat(resp.getBody())
                .as("startup probe: тело содержит status UP")
                .contains("\"status\":\"UP\"");
    }

    // ------------------------------------------------------------------ //
    // 2. Liveness независима от БД / каналов (iвариант k8s: не рестарт)  //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("liveness остаётся UP при OPEN circuit breaker (k8s pod не рестартится)")
    void livenessRemainsUpWhenCircuitBreakerIsOpen() {
        // Симулируем открытый circuit breaker напрямую через JDBC
        jdbcTemplate.update("""
                INSERT INTO channel_circuit_breakers (channel, state, consecutive_failures, updated_at)
                VALUES ('UPLINK', 'OPEN', 5, NOW())
                ON CONFLICT (channel) DO UPDATE
                    SET state = 'OPEN', consecutive_failures = 5
                """);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/liveness", String.class);

        // Liveness = JVM alive, не зависит от состояния каналов/БД
        assertThat(resp.getStatusCode().value())
                .as("liveness остаётся UP при OPEN breaker: HTTP 200")
                .isEqualTo(200);
        assertThat(resp.getBody())
                .as("liveness: status=UP при OPEN breaker (не деградирует)")
                .contains("\"status\":\"UP\"");
    }

    // ------------------------------------------------------------------ //
    // 3. Readiness деградирует при проблемах с критичными каналами        //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("readiness → DOWN (HTTP 503) при OPEN circuit breaker на UPLINK-канале")
    void readinessDownWhenCircuitBreakerIsOpen() {
        // Симулируем открытый circuit breaker (исходящий канал UPLINK недоступен)
        jdbcTemplate.update("""
                INSERT INTO channel_circuit_breakers (channel, state, consecutive_failures, updated_at)
                VALUES ('UPLINK', 'OPEN', 5, NOW())
                ON CONFLICT (channel) DO UPDATE
                    SET state = 'OPEN', consecutive_failures = 5
                """);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);

        // При DOWN readiness-группы Spring Boot возвращает HTTP 503
        assertThat(resp.getStatusCode().value())
                .as("readiness DOWN при OPEN breaker: HTTP 503")
                .isEqualTo(503);
        assertThat(resp.getBody())
                .as("readiness: тело содержит статус DOWN")
                .contains("DOWN");
        // Probe-тело (show-details: never) НЕ должно раскрывать анониму внутренности —
        // ни имя доменного индикатора, ни имена каналов.
        assertThat(resp.getBody())
                .as("readiness probe не раскрывает детали анониму (show-details: never)")
                .doesNotContain("integrationChannels")
                .doesNotContain("circuitBreakers")
                .doesNotContain("UPLINK");

        // Что именно деградировало (integrationChannels с OPEN-каналом) видно ТОЛЬКО через
        // полный /actuator/health за RBAC SYSTEM_ADMIN — там show-details: always.
        var adminResp = restTemplate.exchange(
                "/actuator/health",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(authHeaders(getAdminToken())),
                String.class);
        assertThat(adminResp.getBody())
                .as("полный health (admin) раскрывает причину: integrationChannels DOWN с OPEN-каналом")
                .contains("integrationChannels")
                .contains("UPLINK");
    }

    @Test
    @DisplayName("readiness → OUT_OF_SERVICE (HTTP 503) при DLQ выше критичного порога")
    void readinessOutOfServiceWhenDlqAboveThreshold() {
        // Вставляем 101 DLQ-запись (NEW) — превышаем порог 100 (app.health.dlq-critical-size)
        jdbcTemplate.execute("""
                INSERT INTO dead_letter_messages
                    (source, raw_payload, reason, status, created_at, attempts)
                SELECT 'RAW_GATEWAY', 'test-raw-payload', 'test-reason', 'NEW', NOW(), 0
                FROM generate_series(1, 101)
                """);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);

        // OUT_OF_SERVICE → HTTP 503 (тот же код, что DOWN — Spring Boot StatusAggregator)
        assertThat(resp.getStatusCode().value())
                .as("readiness OUT_OF_SERVICE при DLQ > порога: HTTP 503")
                .isEqualTo(503);
        assertThat(resp.getBody())
                .as("readiness: тело содержит OUT_OF_SERVICE")
                .contains("OUT_OF_SERVICE");
    }

    // ------------------------------------------------------------------ //
    // 4. readiness включает доменный индикатор БД                        //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("readiness probe скрывает детали анониму (show-details: never) — не течёт db/PostgreSQL")
    void readinessProbeHidesDetailsFromAnonymous() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health/readiness", String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // show-details: never на probe-группе — тело только {"status":"UP"}, без компонентов.
        // Анонимный k8s-probe-вызов не должен раскрывать тип СУБД и внутренние имена.
        assertThat(resp.getBody())
                .as("readiness probe не раскрывает компонент db / тип СУБД анониму")
                .doesNotContain("\"db\"")
                .doesNotContain("PostgreSQL")
                .doesNotContain("integrationChannels");

        // Компонент db виден ТОЛЬКО через полный /actuator/health за RBAC SYSTEM_ADMIN.
        var adminResp = restTemplate.exchange(
                "/actuator/health",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(authHeaders(getAdminToken())),
                String.class);
        assertThat(adminResp.getBody())
                .as("полный health (admin) содержит компонент db")
                .contains("\"db\"");
    }

    // ------------------------------------------------------------------ //
    // 5. Полный /actuator/health — за RBAC SYSTEM_ADMIN                  //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("/actuator/health (полный) → 401 для анонима (защищён RBAC SYSTEM_ADMIN)")
    void fullHealthEndpointRequiresAuth() {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/actuator/health", String.class);

        assertThat(resp.getStatusCode().value())
                .as("/actuator/health без JWT: 401 (RBAC SYSTEM_ADMIN)")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("/actuator/health (полный) с admin JWT → 200 + детальная информация")
    void fullHealthWithAdminReturnsDetails() {
        String token = getAdminToken();

        var headers = authHeaders(token);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/actuator/health",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class);

        assertThat(resp.getStatusCode().value())
                .as("/actuator/health с admin JWT: HTTP 200")
                .isEqualTo(200);
        assertThat(resp.getBody())
                .as("полный health содержит db-компонент")
                .contains("\"db\"");
    }
}
