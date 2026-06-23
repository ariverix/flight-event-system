package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.eventhandling.dto.FolderCreateRequest;
import ru.protectinfotrans.eca.sequence.dto.SequenceCreateRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-1: RBAC user-rights — проверки доступа можно/нельзя по гранулярным правам (а не по роли).
 * OPERATOR имеет операционные права (VIEW_SEQUENCES, MANAGE_EXECUTIONS, VIEW_TEMPLATES,
 * MANAGE_DLQ, MANAGE_EVENT_HANDLING, ...), но НЕ MANAGE_SEQUENCES и НЕ MANAGE_USERS; ADMIN — всё.
 */
@DisplayName("P4-1: RBAC user-rights — матрица доступа можно/нельзя")
class P4_1_RbacScenarioIntTest extends BaseIntegrationTest {

    private <T> ResponseEntity<String> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(token)), String.class);
    }

    @Nested
    @DisplayName("Неаутентифицированный доступ")
    class UnauthenticatedTests {

        @Test
        @DisplayName("GET /api/v1/sequences без JWT -> 401")
        void protectedEndpointRequiresAuth() {
            assertThat(restTemplate.getForEntity("/api/v1/sequences", String.class)
                    .getStatusCode().value()).isEqualTo(401);
        }

        @Test
        @DisplayName("default-deny: неизвестный /api/** путь без JWT -> 401 (не permitAll), закрыт backlog P0-3")
        void unmatchedApiPathIsDeniedByDefault() {
            assertThat(restTemplate.getForEntity("/api/v1/this-endpoint-does-not-exist", String.class)
                    .getStatusCode().value()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("OPERATOR — операционные права без управления последовательностями/пользователями")
    class OperatorTests {

        @Test
        @DisplayName("OPERATOR может читать последовательности (VIEW_SEQUENCES) -> 200")
        void operatorCanViewSequences() {
            assertThat(get("/api/v1/sequences", getOperatorToken()).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("OPERATOR может читать шаблоны (VIEW_TEMPLATES) -> 200")
        void operatorCanViewTemplates() {
            assertThat(get("/api/v1/templates", getOperatorToken()).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("OPERATOR может работать с DLQ (MANAGE_DLQ) -> 200")
        void operatorCanAccessDlq() {
            assertThat(get("/api/v1/dlq", getOperatorToken()).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("OPERATOR может создавать папки/обработчики (MANAGE_EVENT_HANDLING) -> 201")
        void operatorCanManageEventHandling() {
            ResponseEntity<String> resp = post("/api/v1/folders",
                    new FolderCreateRequest("Папка оператора", null), getOperatorToken());
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("OPERATOR НЕ может создавать последовательность (нет MANAGE_SEQUENCES) -> 403")
        void operatorCannotManageSequences() {
            ResponseEntity<String> resp = post("/api/v1/sequences",
                    new SequenceCreateRequest("RBAC seq", "x", null, null), getOperatorToken());
            assertThat(resp.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("OPERATOR НЕ может управлять пользователями (нет MANAGE_USERS) -> 403")
        void operatorCannotManageUsers() {
            assertThat(get("/api/v1/users", getOperatorToken()).getStatusCode().value()).isEqualTo(403);
        }
    }

    @Nested
    @DisplayName("ADMIN — полный доступ")
    class AdminTests {

        @Test
        @DisplayName("ADMIN может создавать последовательность (MANAGE_SEQUENCES) -> 201")
        void adminCanManageSequences() {
            ResponseEntity<String> resp = post("/api/v1/sequences",
                    new SequenceCreateRequest("Admin seq", "x", null, null), getAdminToken());
            assertThat(resp.getStatusCode().value()).isEqualTo(201);
        }

        @Test
        @DisplayName("ADMIN может управлять пользователями (MANAGE_USERS) -> 200")
        void adminCanManageUsers() {
            assertThat(get("/api/v1/users", getAdminToken()).getStatusCode().value()).isEqualTo(200);
        }
    }
}
