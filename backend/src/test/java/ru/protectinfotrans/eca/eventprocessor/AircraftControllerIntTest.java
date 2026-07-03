package ru.protectinfotrans.eca.eventprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Фаза 5 (прогон апгрейда): интеграционные тесты {@code GET /api/v1/aircraft} — список бортов
 * (tail numbers) для UI aircraft-bindings. RBAC {@code VIEW_SEQUENCES}, пагинация, поиск,
 * корректность GROUP BY-агрегатов над журналом сообщений. Демо-борт VP-BQR, рейс SU1234.
 */
@DisplayName("Фаза 5: GET /api/v1/aircraft — список бортов из журнала сообщений")
class AircraftControllerIntTest extends BaseIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private void insertMessage(String aircraftId, String flightNumber, LocalDateTime receivedAt) {
        jdbcTemplate.update(
                "INSERT INTO messages (message_type, template_name, aircraft_id, flight_number, "
                        + "content, received_at, is_estimated_position) "
                        + "VALUES ('DOWNLINK', 'STATUS', ?, ?, 'body', ?, false)",
                aircraftId, flightNumber, receivedAt);
    }

    private JsonNode getAircraft(String query, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/aircraft" + query, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return mapper.readTree(resp.getBody());
    }

    @Test
    @DisplayName("без JWT → 401 (за RBAC VIEW_SEQUENCES)")
    void requiresAuth() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/aircraft", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("OPERATOR видит различные борта с агрегатами (кол-во сообщений, рейсов, последний контакт)")
    void listsDistinctAircraftWithAggregates() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        // VP-BQR: 3 сообщения, 2 рейса
        insertMessage("VP-BQR", "SU1234", now.minusHours(2));
        insertMessage("VP-BQR", "SU1234", now.minusHours(1));
        insertMessage("VP-BQR", "SU5678", now.minusMinutes(10));
        // VP-AAA: 1 сообщение, 1 рейс
        insertMessage("VP-AAA", "SU0001", now.minusHours(5));
        // сообщение без aircraft_id — не должно попасть в список бортов
        insertMessage(null, "SU9999", now);

        JsonNode body = getAircraft("", getOperatorToken());

        assertThat(body.get("totalElements").asInt()).isEqualTo(2); // VP-BQR + VP-AAA, null исключён
        JsonNode content = body.get("content");
        // сортировка: последний контакт сверху → VP-BQR (10 мин назад) перед VP-AAA (5 ч назад)
        assertThat(content.get(0).get("aircraftId").asText()).isEqualTo("VP-BQR");
        assertThat(content.get(0).get("messageCount").asLong()).isEqualTo(3);
        assertThat(content.get(0).get("flightCount").asLong()).isEqualTo(2);
        assertThat(content.get(0).get("lastSeen").asText()).isNotBlank();
        assertThat(content.get(1).get("aircraftId").asText()).isEqualTo("VP-AAA");
        assertThat(content.get(1).get("messageCount").asLong()).isEqualTo(1);
        assertThat(content.get(1).get("flightCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("поиск по подстроке tail number (case-insensitive)")
    void searchFiltersByTailNumber() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        insertMessage("VP-BQR", "SU1234", now);
        insertMessage("VP-AAA", "SU0001", now);
        insertMessage("EI-XYZ", "EI0002", now);

        JsonNode body = getAircraft("?search=vp-", getOperatorToken());

        assertThat(body.get("totalElements").asInt()).isEqualTo(2); // VP-BQR, VP-AAA
        for (JsonNode n : body.get("content")) {
            assertThat(n.get("aircraftId").asText()).startsWith("VP-");
        }
    }

    @Test
    @DisplayName("пагинация: size ограничивает страницу, totalElements считает все борта")
    void paginationBoundsPageButCountsAll() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 5; i++) {
            insertMessage("VP-" + i, "SU000" + i, now.minusMinutes(i));
        }

        JsonNode body = getAircraft("?page=0&size=2", getOperatorToken());

        assertThat(body.get("totalElements").asInt()).isEqualTo(5);
        assertThat(body.get("content").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("нет сообщений → пустой список, 200")
    void emptyWhenNoMessages() throws Exception {
        JsonNode body = getAircraft("", getAdminToken());

        assertThat(body.get("totalElements").asInt()).isEqualTo(0);
        assertThat(body.get("content").size()).isEqualTo(0);
    }
}
