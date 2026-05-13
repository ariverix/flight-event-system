package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты JWT-аутентификации с реальной PostgreSQL.
 * Проверяет полный цикл: логин → токен → защищённый эндпоинт (UC-09).
 *
 * См. диплом: раздел 1.3.5 (UC-09), Глава 3 (Тестирование)
 */
@DisplayName("Authentication Integration Tests")
class AuthenticationIntTest extends BaseIntegrationTest {

    @Test
    @DisplayName("Логин с корректными данными должен вернуть JWT-токен")
    void loginWithValidCredentials_returnsJwtToken() {
        LoginRequest request = new LoginRequest("admin", "admin");

        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                request,
                LoginResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().username()).isEqualTo("admin");
        assertThat(response.getBody().role().name()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Логин с неверным паролем должен вернуть 401")
    void loginWithWrongPassword_returns401() {
        LoginRequest request = new LoginRequest("admin", "wrong_password");

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                request,
                Map.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Запрос к защищённому эндпоинту без токена должен вернуть 401")
    void accessProtectedEndpointWithoutToken_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/sequences",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Запрос с невалидным JWT должен вернуть 401")
    void accessProtectedEndpointWithInvalidToken_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("invalid.jwt.token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sequences",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Запрос с валидным JWT должен вернуть 200")
    void accessProtectedEndpointWithValidToken_returns200() {
        String token = getAdminToken();
        HttpHeaders headers = authHeaders(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/sequences",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
