package ru.protectinfotrans.eca.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5-1: метрики Micrometer → Prometheus. Проверяет, что scrape-эндпоинт отдаёт бизнес- и
 * технические метрики (JVM, пул БД), и что он за RBAC (как весь /actuator/**, P4-1).
 */
// @AutoConfigureObservability: @SpringBootTest по умолчанию ОТКЛЮЧАЕТ экспорт метрик (prometheus
// registry/endpoint), чтобы не засорять тесты; здесь явно включаем — проверяем именно scrape-эндпоинт.
@AutoConfigureObservability
@DisplayName("P5-1: /actuator/prometheus — бизнес+технические метрики")
class P5_1_MetricsScenarioIntTest extends BaseIntegrationTest {

    @Test
    @DisplayName("без JWT -> 401 (actuator за RBAC SYSTEM_ADMIN)")
    void prometheusRequiresAuth() {
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("с admin JWT -> 200 и экспонированы бизнес+JVM+пул БД метрики")
    void prometheusExposesMetrics() {
        String token = getAdminToken();
        ResponseEntity<String> resp = restTemplate.exchange("/actuator/prometheus",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        String body = resp.getBody();
        assertThat(body).isNotNull();
        // бизнес-метрика P5-1: gauge активных инстансов (присутствует даже при значении 0)
        assertThat(body).contains("eca_execution_instances_active");
        // существующие бизнес-метрики (P3/P2) тоже экспонируются через общий реестр
        assertThat(body).contains("eca_conditions_active");
        // технические: JVM и пул соединений БД (Hikari) — автопривязка Micrometer
        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("hikaricp_connections");
        // прогон «Промышленный апгрейд»: метрики новых путей регистрируются эагерно
        // (в конструкторах ExecutionMetrics / RateLimitFilter) и видны в scrape даже при значении 0
        assertThat(body).contains("eca_execution_start_duplicate_rejected_total"); // Фаза 1 (dedup V38)
        assertThat(body).contains("eca_ratelimit_rejected_total");                 // Фаза 3 (429)
    }
}
