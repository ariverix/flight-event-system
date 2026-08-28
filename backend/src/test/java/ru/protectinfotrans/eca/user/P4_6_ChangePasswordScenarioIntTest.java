package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.user.dto.ChangePasswordRequest;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RefreshRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-6 (backlog из PRODUCTION_READINESS_REPORT.md, раздел "Безопасность" — "эндпоинта смены пароля
 * нет"): self-service смена пароля — PUT /api/v1/auth/password.
 *
 * <p>Использует стандартного тестового оператора {@code op_test}/{@code op_password}, создаваемого
 * {@link BaseIntegrationTest#getOperatorToken()}.
 */
@DisplayName("P4-6: self-service смена пароля")
class P4_6_ChangePasswordScenarioIntTest extends BaseIntegrationTest {

    private static final String USERNAME = "op_test";
    private static final String OLD_PASSWORD = "op_password";
    private static final String NEW_PASSWORD = "new_op_password";

    private int countByAction(String action) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class, action);
        return c == null ? 0 : c;
    }

    private LoginResponse login(String username, String password) {
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(username, password), LoginResponse.class).getBody();
    }

    private ResponseEntity<String> changePassword(String accessToken, String currentPassword, String newPassword) {
        return restTemplate.exchange("/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(new ChangePasswordRequest(currentPassword, newPassword), authHeaders(accessToken)),
                String.class);
    }

    @Test
    @DisplayName("успешная смена пароля -> 204, логин старым паролем -> 401, логин новым -> 200")
    void changePasswordSucceedsAndOldPasswordStopsWorking() {
        String accessToken = getOperatorToken();

        ResponseEntity<String> resp = changePassword(accessToken, OLD_PASSWORD, NEW_PASSWORD);
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> loginWithOld = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(USERNAME, OLD_PASSWORD), String.class);
        assertThat(loginWithOld.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<LoginResponse> loginWithNew = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(USERNAME, NEW_PASSWORD), LoginResponse.class);
        assertThat(loginWithNew.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("смена пароля отзывает все существующие refresh-токены -> refresh старым токеном 401")
    void changePasswordRevokesAllRefreshTokens() {
        getOperatorToken(); // создаёт пользователя op_test
        LoginResponse initialLogin = login(USERNAME, OLD_PASSWORD);

        ResponseEntity<String> resp = changePassword(initialLogin.token(), OLD_PASSWORD, NEW_PASSWORD);
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> refreshAfterChange = restTemplate.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(initialLogin.refreshToken()), String.class);
        assertThat(refreshAfterChange.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("неверный текущий пароль -> 400, пароль не меняется")
    void wrongCurrentPasswordIsRejected() {
        String accessToken = getOperatorToken();

        ResponseEntity<String> resp = changePassword(accessToken, "wrong-current", NEW_PASSWORD);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);

        // старый пароль всё ещё работает
        ResponseEntity<LoginResponse> loginWithOld = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(USERNAME, OLD_PASSWORD), LoginResponse.class);
        assertThat(loginWithOld.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("слишком короткий новый пароль -> 400 (bean validation)")
    void tooShortNewPasswordIsRejected() {
        String accessToken = getOperatorToken();

        ResponseEntity<String> resp = changePassword(accessToken, OLD_PASSWORD, "abc");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("неаутентифицированный запрос -> 401")
    void unauthenticatedRequestIsRejected() {
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/auth/password", HttpMethod.PUT,
                new HttpEntity<>(new ChangePasswordRequest("any", "newpassword")), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("успешная смена пароля пишет USER_PASSWORD_CHANGED в audit_log с userId и correlationId")
    void changePasswordIsAudited() {
        String accessToken = getOperatorToken();

        changePassword(accessToken, OLD_PASSWORD, NEW_PASSWORD);

        assertThat(countByAction("USER_PASSWORD_CHANGED")).isGreaterThanOrEqualTo(1);
        String correlationId = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM audit_log WHERE action='USER_PASSWORD_CHANGED' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(correlationId).isNotBlank();
        Integer withUserId = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='USER_PASSWORD_CHANGED' AND user_id IS NOT NULL",
                Integer.class);
        assertThat(withUserId).isGreaterThanOrEqualTo(1);
    }
}
