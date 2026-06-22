package ru.protectinfotrans.eca.customfields;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.MessageType;
import ru.protectinfotrans.eca.customfields.domain.ExtractionSource;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleCreateRequest;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleResponse;
import ru.protectinfotrans.eca.customfields.dto.CustomFieldRuleUpdateRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-2: интеграционные тесты REST API CRUD правил извлечения custom fields — с реальным
 * PostgreSQL (через {@link BaseIntegrationTest}) и реальным HTTP/JWT-стеком.
 *
 * Покрывает: создание/уникальность имени/обновление (имя неизменно)/удаление, валидацию
 * паттерна (CONTENT без capturing-группы -> 400), фильтрацию списка, и RBAC (эндпоинт защищён —
 * не {@code permitAll()}, запрос без токена должен получить 401, тот же принцип, что у
 * {@code TemplateControllerIntTest}, P3-1).
 */
@DisplayName("Custom Field Rule Controller Integration Tests (P3-2)")
class CustomFieldRuleControllerIntTest extends BaseIntegrationTest {

    private HttpHeaders headers;

    @BeforeEach
    void obtainToken() {
        headers = authHeaders(getAdminToken());
    }

    private CustomFieldRuleCreateRequest contentRequest(String name) {
        return new CustomFieldRuleCreateRequest(
                name, "Gate number from STATUS message", MessageType.DOWNLINK, "STATUS",
                ExtractionSource.CONTENT, "GATE=(\\w+)", null);
    }

    @Test
    @DisplayName("Создание правила CONTENT должно вернуть 201, активно по умолчанию")
    void createRule_returns201WithDefaults() {
        ResponseEntity<CustomFieldRuleResponse> response = restTemplate.exchange(
                "/api/v1/custom-field-rules",
                HttpMethod.POST,
                new HttpEntity<>(contentRequest("GATE_NUMBER_IT"), headers),
                CustomFieldRuleResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("GATE_NUMBER_IT");
        assertThat(response.getBody().messageType()).isEqualTo(MessageType.DOWNLINK);
        assertThat(response.getBody().extractionSource()).isEqualTo(ExtractionSource.CONTENT);
        assertThat(response.getBody().active()).isTrue();
    }

    @Test
    @DisplayName("Создание правила с уже существующим именем должно вернуть 409")
    void createRule_duplicateName_returns409() {
        restTemplate.exchange("/api/v1/custom-field-rules", HttpMethod.POST,
                new HttpEntity<>(contentRequest("DUPLICATE_RULE_IT"), headers), CustomFieldRuleResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/custom-field-rules",
                HttpMethod.POST,
                new HttpEntity<>(contentRequest("DUPLICATE_RULE_IT"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Создание правила CONTENT без capturing-группы в regex должно вернуть 400")
    void createRule_contentWithoutCapturingGroup_returns400() {
        CustomFieldRuleCreateRequest request = new CustomFieldRuleCreateRequest(
                "BAD_PATTERN_IT", null, MessageType.DOWNLINK, "STATUS",
                ExtractionSource.CONTENT, "GATE=\\w+", true);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/custom-field-rules",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Полный цикл: создание -> получение по id -> обновление (имя неизменно) -> удаление -> 404")
    void fullCycle_createGetUpdateDelete() {
        ResponseEntity<CustomFieldRuleResponse> createResp = restTemplate.exchange(
                "/api/v1/custom-field-rules", HttpMethod.POST,
                new HttpEntity<>(contentRequest("FULL_CYCLE_RULE_IT"), headers), CustomFieldRuleResponse.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long id = createResp.getBody().id();

        ResponseEntity<CustomFieldRuleResponse> getResp = restTemplate.exchange(
                "/api/v1/custom-field-rules/" + id, HttpMethod.GET,
                new HttpEntity<>(headers), CustomFieldRuleResponse.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().name()).isEqualTo("FULL_CYCLE_RULE_IT");

        CustomFieldRuleUpdateRequest updateRequest = new CustomFieldRuleUpdateRequest(
                "Updated description", MessageType.DOWNLINK, "OOOI",
                ExtractionSource.METADATA, "paxCount", false);
        ResponseEntity<CustomFieldRuleResponse> updateResp = restTemplate.exchange(
                "/api/v1/custom-field-rules/" + id, HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers), CustomFieldRuleResponse.class);
        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody().name()).isEqualTo("FULL_CYCLE_RULE_IT"); // имя неизменно
        assertThat(updateResp.getBody().templateName()).isEqualTo("OOOI");
        assertThat(updateResp.getBody().extractionSource()).isEqualTo(ExtractionSource.METADATA);
        assertThat(updateResp.getBody().pattern()).isEqualTo("paxCount");
        assertThat(updateResp.getBody().active()).isFalse();

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/custom-field-rules/" + id, HttpMethod.DELETE,
                new HttpEntity<>(headers), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterDeleteResp = restTemplate.exchange(
                "/api/v1/custom-field-rules/" + id, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(afterDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Список правил с фильтром по messageType должен вернуть 200 и пагинацию")
    void listRules_filteredByMessageType_returnsPaginatedResult() {
        restTemplate.exchange("/api/v1/custom-field-rules", HttpMethod.POST,
                new HttpEntity<>(contentRequest("LIST_FILTER_RULE_IT"), headers), CustomFieldRuleResponse.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/custom-field-rules?page=0&size=10&messageType=DOWNLINK",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("totalElements", "content", "LIST_FILTER_RULE_IT");
    }

    @Test
    @DisplayName("RBAC: /api/v1/custom-field-rules НЕ открытый эндпоинт — запрос без токена должен вернуть 401")
    void accessRulesEndpointWithoutToken_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/custom-field-rules",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("RBAC: создание правила без токена должно вернуть 401 (catch-all permitAll не применяется)")
    void createRuleWithoutToken_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/custom-field-rules",
                HttpMethod.POST,
                new HttpEntity<>(contentRequest("NO_TOKEN_RULE_IT"), new HttpHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
