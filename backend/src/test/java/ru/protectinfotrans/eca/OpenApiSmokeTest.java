package ru.protectinfotrans.eca;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke-тест генерации OpenAPI и доступности Swagger UI.
 *
 * Защищает от регрессии в SecurityConfig: пути springdoc должны оставаться
 * доступными без JWT (permitAll), иначе документация перестанет открываться
 * без токена. Также проверяет, что в сгенерированной спецификации присутствуют
 * пути ключевых контроллеров.
 */
class OpenApiSmokeTest extends BaseIntegrationTest {

    @Test
    void apiDocsAvailableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotBlank();
        assertThat(body).contains("\"openapi\"");
        assertThat(body).contains("/api/v1/sequences");
        assertThat(body).contains("/api/v1/executions");
        assertThat(body).contains("/api/v1/auth/login");
        assertThat(body).contains("/api/v1/messages/incoming");
        assertThat(body).contains("bearerAuth");
    }

    @Test
    void swaggerUiAvailableWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Swagger UI");
    }
}
