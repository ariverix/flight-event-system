package ru.protectinfotrans.eca.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.sequence.domain.UplinkOrigin;
import ru.protectinfotrans.eca.templates.dto.TemplateCreateRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateRenderRequest;
import ru.protectinfotrans.eca.templates.dto.TemplateRenderResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateResponse;
import ru.protectinfotrans.eca.templates.dto.TemplateUpdateRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-1: интеграционные тесты REST API CRUD шаблонов сообщений — с реальным PostgreSQL
 * (через {@link BaseIntegrationTest}) и реальным HTTP/JWT-стеком.
 *
 * Покрывает: создание/уникальность имени/обновление/удаление, рендеринг по имени,
 * фильтрацию списка, и RBAC (эндпоинт защищён — не {@code permitAll()}, запрос без
 * токена должен получить 401, см. прецедент {@code AuthenticationIntTest}).
 */
@DisplayName("Template Controller Integration Tests (P3-1)")
class TemplateControllerIntTest extends BaseIntegrationTest {

    private HttpHeaders headers;

    @BeforeEach
    void obtainToken() {
        headers = authHeaders(getAdminToken());
    }

    private TemplateCreateRequest uplinkRequest(String name) {
        return new TemplateCreateRequest(
                name, "Request current position", MessageType.UPLINK,
                UplinkOrigin.COMPUTER_GENERATED, "POSITION",
                "Please report position, ETA {{eta}}", null);
    }

    @Test
    @DisplayName("Создание UPLINK-шаблона должно вернуть 201, активный по умолчанию")
    void createTemplate_returns201WithDefaults() {
        ResponseEntity<TemplateResponse> response = restTemplate.exchange(
                "/api/v1/templates",
                HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("REQUEST_POSITION_IT"), headers),
                TemplateResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("REQUEST_POSITION_IT");
        assertThat(response.getBody().messageType()).isEqualTo(MessageType.UPLINK);
        assertThat(response.getBody().origin()).isEqualTo(UplinkOrigin.COMPUTER_GENERATED);
        assertThat(response.getBody().active()).isTrue();
        assertThat(response.getBody().variableNames()).containsExactly("eta");
    }

    @Test
    @DisplayName("Создание шаблона с уже существующим именем должно вернуть 409")
    void createTemplate_duplicateName_returns409() {
        restTemplate.exchange("/api/v1/templates", HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("DUPLICATE_NAME_IT"), headers), TemplateResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates",
                HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("DUPLICATE_NAME_IT"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Создание UPLINK-шаблона без origin должно вернуть 400")
    void createTemplate_uplinkWithoutOrigin_returns400() {
        TemplateCreateRequest request = new TemplateCreateRequest(
                "BAD_NO_ORIGIN_IT", null, MessageType.UPLINK, null, null, "body {{x}}", true);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Полный цикл: создание -> получение по id -> обновление -> получение по имени -> удаление -> 404")
    void fullCycle_createGetUpdateGetByNameDelete() {
        ResponseEntity<TemplateResponse> createResp = restTemplate.exchange(
                "/api/v1/templates", HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("FULL_CYCLE_IT"), headers), TemplateResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = createResp.getBody().id();

        ResponseEntity<TemplateResponse> getResp = restTemplate.exchange(
                "/api/v1/templates/" + id, HttpMethod.GET,
                new HttpEntity<>(headers), TemplateResponse.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().name()).isEqualTo("FULL_CYCLE_IT");

        TemplateUpdateRequest updateRequest = new TemplateUpdateRequest(
                "Updated description", MessageType.UPLINK, UplinkOrigin.EXTERNAL_USER,
                "WEATHER", "New body {{eta}} {{flight}}", false);
        ResponseEntity<TemplateResponse> updateResp = restTemplate.exchange(
                "/api/v1/templates/" + id, HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), TemplateResponse.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody().name()).isEqualTo("FULL_CYCLE_IT"); // имя неизменно
        assertThat(updateResp.getBody().origin()).isEqualTo(UplinkOrigin.EXTERNAL_USER);
        assertThat(updateResp.getBody().category()).isEqualTo("WEATHER");
        assertThat(updateResp.getBody().active()).isFalse();
        assertThat(updateResp.getBody().variableNames()).containsExactlyInAnyOrder("eta", "flight");

        ResponseEntity<TemplateResponse> byNameResp = restTemplate.exchange(
                "/api/v1/templates/by-name/FULL_CYCLE_IT", HttpMethod.GET,
                new HttpEntity<>(headers), TemplateResponse.class);
        assertThat(byNameResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byNameResp.getBody().id()).isEqualTo(id);

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/templates/" + id, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterDeleteResp = restTemplate.exchange(
                "/api/v1/templates/" + id, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(afterDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Список шаблонов с фильтром по messageType должен вернуть 200 и пагинацию")
    void listTemplates_filteredByMessageType_returnsPaginatedResult() {
        restTemplate.exchange("/api/v1/templates", HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("LIST_FILTER_IT"), headers), TemplateResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates?page=0&size=10&messageType=UPLINK",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("totalElements", "content", "LIST_FILTER_IT");
    }

    @Test
    @DisplayName("Рендеринг шаблона по имени должен подставить переменные и вернуть 200")
    void renderTemplate_returnsRenderedText() {
        restTemplate.exchange("/api/v1/templates", HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("RENDER_IT"), headers), TemplateResponse.class);

        TemplateRenderRequest renderRequest = new TemplateRenderRequest("RENDER_IT", Map.of("eta", "12:30"));
        ResponseEntity<TemplateRenderResponse> response = restTemplate.exchange(
                "/api/v1/templates/render", HttpMethod.POST,
                new HttpEntity<>(renderRequest, headers), TemplateRenderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().renderedText()).isEqualTo("Please report position, ETA 12:30");
    }

    @Test
    @DisplayName("Рендеринг с отсутствующей переменной должен вернуть 400")
    void renderTemplate_missingVariable_returns400() {
        restTemplate.exchange("/api/v1/templates", HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("RENDER_MISSING_VAR_IT"), headers), TemplateResponse.class);

        TemplateRenderRequest renderRequest = new TemplateRenderRequest("RENDER_MISSING_VAR_IT", Map.of());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates/render", HttpMethod.POST,
                new HttpEntity<>(renderRequest, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("RBAC: /api/v1/templates НЕ открытый эндпоинт — запрос без токена должен вернуть 401")
    void accessTemplatesEndpointWithoutToken_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("RBAC: создание шаблона без токена должно вернуть 401 (catch-all permitAll не применяется)")
    void createTemplateWithoutToken_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/templates",
                HttpMethod.POST,
                new HttpEntity<>(uplinkRequest("NO_TOKEN_IT"), new HttpHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
