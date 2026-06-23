package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RefreshRequest;
import ru.protectinfotrans.eca.user.dto.RegisterRequest;
import ru.protectinfotrans.eca.user.domain.Role;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-5: полный audit_log действий пользователя — кто/что/когда (логин/выход, неудачный вход,
 * управление пользователями), с correlationId для связи со структурными логами.
 */
@DisplayName("P4-5: audit_log действий пользователя")
class P4_5_AuditScenarioIntTest extends BaseIntegrationTest {

    private int countByAction(String action) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = ?", Integer.class, action);
        return c == null ? 0 : c;
    }

    private LoginResponse loginAdmin() {
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest("admin", "admin"), LoginResponse.class).getBody();
    }

    @Test
    @DisplayName("успешный вход пишет USER_LOGIN с проставленным correlationId (кто/что/когда)")
    void successfulLoginIsAudited() {
        loginAdmin();

        assertThat(countByAction("USER_LOGIN")).isGreaterThanOrEqualTo(1);
        // correlationId проставляется фильтром на HTTP-запрос и пишется в audit (P4-5)
        String correlationId = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM audit_log WHERE action='USER_LOGIN' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(correlationId).isNotBlank();
        // who/when: userId и created_at заполнены
        Integer withWhoWhen = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action='USER_LOGIN' AND user_id IS NOT NULL AND created_at IS NOT NULL",
                Integer.class);
        assertThat(withWhoWhen).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("неудачный вход (неверный пароль) пишет USER_LOGIN_FAILED с причиной")
    void failedLoginIsAudited() {
        restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest("admin", "wrong-password"), String.class);

        assertThat(countByAction("USER_LOGIN_FAILED")).isGreaterThanOrEqualTo(1);
        String details = jdbcTemplate.queryForObject(
                "SELECT details_json::text FROM audit_log WHERE action='USER_LOGIN_FAILED' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(details).contains("BAD_PASSWORD").contains("admin");
    }

    @Test
    @DisplayName("неудачный вход несуществующего пользователя тоже аудируется")
    void failedLoginUnknownUserIsAudited() {
        restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest("no-such-user", "x"), String.class);

        String details = jdbcTemplate.queryForObject(
                "SELECT details_json::text FROM audit_log WHERE action='USER_LOGIN_FAILED' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(details).contains("USER_NOT_FOUND").contains("no-such-user");
    }

    @Test
    @DisplayName("выход пишет USER_LOGOUT")
    void logoutIsAudited() {
        LoginResponse login = loginAdmin();
        restTemplate.postForEntity("/api/v1/auth/logout",
                new RefreshRequest(login.refreshToken()), Void.class);

        assertThat(countByAction("USER_LOGOUT")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("создание пользователя (управление правами) пишет CREATE_USER")
    void userManagementIsAudited() {
        String adminToken = loginAdmin().token();
        restTemplate.postForEntity("/api/v1/auth/register",
                new HttpEntity<>(new RegisterRequest("audit_op", "pw1234", "Audit Op", Role.OPERATOR),
                        authHeaders(adminToken)), String.class);

        assertThat(countByAction("CREATE_USER")).isGreaterThanOrEqualTo(1);
        String details = jdbcTemplate.queryForObject(
                "SELECT details_json::text FROM audit_log WHERE action='CREATE_USER' ORDER BY id DESC LIMIT 1",
                String.class);
        assertThat(details).contains("audit_op").contains("OPERATOR");
    }

    @Test
    @DisplayName("GET /api/v1/audit-log доступен админу (VIEW_AUDIT_LOG) -> 200")
    void auditLogReadableByAdmin() {
        String adminToken = loginAdmin().token();
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/audit-log",
                HttpMethod.GET, new HttpEntity<>(authHeaders(adminToken)), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }
}
