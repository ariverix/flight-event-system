package ru.protectinfotrans.eca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозная интеграционная проверка {@link CorrelationIdFilter} на реальном HTTP-стеке
 * приложения (Spring Security + сериализация ответа), а не только на уровне unit-теста
 * фильтра. Подтверждает, что фильтр реально зарегистрирован в цепочке сервлет-фильтров.
 */
@DisplayName("CorrelationIdFilter — интеграционная проверка HTTP-стека")
class CorrelationIdFilterIntTest extends BaseIntegrationTest {

    @Test
    @DisplayName("ответ должен содержать сгенерированный X-Correlation-Id, если запрос пришёл без него")
    void responseContainsGeneratedCorrelationIdWhenAbsent() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        String correlationId = response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(correlationId).isNotBlank();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    @DisplayName("ответ должен содержать тот же X-Correlation-Id, что был передан в запросе")
    void responsePropagatesIncomingCorrelationId() {
        String incoming = UUID.randomUUID().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.add(CorrelationIdFilter.CORRELATION_ID_HEADER, incoming);

        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/health",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(incoming);
    }
}
