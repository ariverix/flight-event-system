package ru.protectinfotrans.eca.sequence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.PageResponse;
import ru.protectinfotrans.eca.sequence.domain.SequenceStatus;
import ru.protectinfotrans.eca.sequence.domain.StepType;
import ru.protectinfotrans.eca.sequence.domain.TransitionAction;
import ru.protectinfotrans.eca.sequence.dto.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты REST API управления последовательностями.
 * Проверяет полный цикл: создание → добавление шагов → активация (UC-01..UC-04).
 *
 * См. диплом: раздел 1.3.5 (UC-01..UC-04), Глава 3 (Тестирование)
 */
@DisplayName("Sequence Controller Integration Tests")
class SequenceControllerIntTest extends BaseIntegrationTest {

    private HttpHeaders headers;

    @BeforeEach
    void obtainToken() {
        headers = authHeaders(getAdminToken());
    }

    @Test
    @DisplayName("UC-01: Создание последовательности должно вернуть 201 и DRAFT-статус")
    void createSequence_returns201WithDraftStatus() {
        SequenceCreateRequest request = new SequenceCreateRequest(
                "Test Sequence Int", "Integration test sequence", null, null
        );

        ResponseEntity<SequenceResponse> response = restTemplate.exchange(
                "/api/v1/sequences",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                SequenceResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Test Sequence Int");
        assertThat(response.getBody().status()).isEqualTo(SequenceStatus.DRAFT);
        assertThat(response.getBody().steps()).isEmpty();
    }

    @Test
    @DisplayName("UC-01..UC-04: Полный цикл — создание, добавление шага, активация, чтение")
    void fullCycle_createAddStepActivateGet() {
        // UC-01: Создать последовательность со start-критерием
        SequenceCreateRequest createRequest = new SequenceCreateRequest(
                "ACARS Sequence Int Test",
                "Тест полного цикла CRUD",
                "{\"type\":\"FLIGHT_STAGE\",\"operator\":\"EQUALS\",\"targetStage\":\"OFF\"}",
                null
        );
        ResponseEntity<SequenceResponse> createResp = restTemplate.exchange(
                "/api/v1/sequences",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, headers),
                SequenceResponse.class
        );
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long seqId = createResp.getBody().id();
        assertThat(seqId).isNotNull();

        // UC-02: Добавить ACTION-шаг
        StepCreateRequest stepReq = new StepCreateRequest(
                "Send Request Position",
                StepType.ACTION,
                "{\"actionType\":\"SEND_UPLINK\",\"templateName\":\"REQUEST_POSITION\"}",
                null,
                TransitionAction.CONTINUE,
                null,
                false,
                TransitionAction.END,
                null,
                true
        );
        ResponseEntity<StepResponse> stepResp = restTemplate.exchange(
                "/api/v1/sequences/" + seqId + "/steps",
                HttpMethod.POST,
                new HttpEntity<>(stepReq, headers),
                StepResponse.class
        );
        assertThat(stepResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stepResp.getBody().stepType()).isEqualTo(StepType.ACTION);
        assertThat(stepResp.getBody().orderIndex()).isEqualTo(1);
        assertThat(stepResp.getBody().onSuccessAction()).isEqualTo(TransitionAction.CONTINUE);

        // UC-04: Активировать
        ResponseEntity<SequenceResponse> activateResp = restTemplate.exchange(
                "/api/v1/sequences/" + seqId + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                SequenceResponse.class
        );
        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activateResp.getBody().status()).isEqualTo(SequenceStatus.ACTIVE);

        // UC-05: Получить с шагами
        ResponseEntity<SequenceResponse> getResp = restTemplate.exchange(
                "/api/v1/sequences/" + seqId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                SequenceResponse.class
        );
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().steps()).hasSize(1);
        assertThat(getResp.getBody().steps().get(0).stepType()).isEqualTo(StepType.ACTION);
    }

    @Test
    @DisplayName("UC-05: Список последовательностей с пагинацией должен вернуть 200")
    void listSequences_returnsPaginatedResult() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sequences?page=0&size=5",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Ответ содержит пагинационные поля
        assertThat(response.getBody()).contains("totalElements", "content");
    }

    @Test
    @DisplayName("Активация последовательности без шагов должна вернуть 400")
    void activateEmptySequence_returns400() {
        SequenceCreateRequest createRequest = new SequenceCreateRequest(
                "Empty Sequence Inttest", "No steps", null, null
        );
        ResponseEntity<SequenceResponse> createResp = restTemplate.exchange(
                "/api/v1/sequences",
                HttpMethod.POST,
                new HttpEntity<>(createRequest, headers),
                SequenceResponse.class
        );
        Long seqId = createResp.getBody().id();

        ResponseEntity<String> activateResp = restTemplate.exchange(
                "/api/v1/sequences/" + seqId + "/activate",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(activateResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
