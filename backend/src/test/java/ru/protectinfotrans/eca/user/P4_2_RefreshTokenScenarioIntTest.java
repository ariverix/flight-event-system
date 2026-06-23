package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RefreshRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-2: access + refresh JWT с ротацией и инвалидизацией.
 */
@DisplayName("P4-2: refresh-токены — ротация, reuse-detection, logout")
class P4_2_RefreshTokenScenarioIntTest extends BaseIntegrationTest {

    private LoginResponse login() {
        ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest("admin", "admin"), LoginResponse.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody();
    }

    private ResponseEntity<LoginResponse> refresh(String refreshToken) {
        return restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(refreshToken), LoginResponse.class);
    }

    @Test
    @DisplayName("login выдаёт и access, и refresh токен")
    void loginIssuesBothTokens() {
        LoginResponse body = login();
        assertThat(body.token()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("refresh выдаёт новую пару; новый refresh отличается от старого (ротация)")
    void refreshRotatesTokens() {
        LoginResponse initial = login();

        ResponseEntity<LoginResponse> refreshed = refresh(initial.refreshToken());

        assertThat(refreshed.getStatusCode().value()).isEqualTo(200);
        assertThat(refreshed.getBody().token()).isNotBlank();
        assertThat(refreshed.getBody().refreshToken()).isNotBlank();
        assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(initial.refreshToken());
    }

    @Test
    @DisplayName("повторное использование старого refresh после ротации -> 401 (reuse-detection)")
    void reuseOfRotatedTokenIsRejected() {
        LoginResponse initial = login();
        ResponseEntity<LoginResponse> first = refresh(initial.refreshToken());
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        // старый токен уже ротирован -> предъявление повторно отклоняется
        ResponseEntity<String> reuse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(initial.refreshToken()), String.class);
        assertThat(reuse.getStatusCode().value()).isEqualTo(401);

        // reuse-detection отозвал цепочку -> даже валидный новый токен больше не работает
        ResponseEntity<String> chainAfterReuse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(first.getBody().refreshToken()), String.class);
        assertThat(chainAfterReuse.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("logout инвалидирует refresh-токен -> последующий refresh 401")
    void logoutRevokesRefreshToken() {
        LoginResponse initial = login();

        ResponseEntity<Void> logout = restTemplate.postForEntity(
                "/api/v1/auth/logout", new RefreshRequest(initial.refreshToken()), Void.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> afterLogout = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest(initial.refreshToken()), String.class);
        assertThat(afterLogout.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("неизвестный refresh-токен -> 401")
    void unknownRefreshTokenRejected() {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshRequest("garbage-not-a-real-token"), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("новый access-токен после refresh даёт доступ к защищённому эндпоинту")
    void refreshedAccessTokenWorks() {
        LoginResponse initial = login();
        ResponseEntity<LoginResponse> refreshed = refresh(initial.refreshToken());
        String newAccess = refreshed.getBody().token();

        ResponseEntity<String> me = restTemplate.exchange(
                "/api/v1/auth/me", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(authHeaders(newAccess)), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
    }
}
