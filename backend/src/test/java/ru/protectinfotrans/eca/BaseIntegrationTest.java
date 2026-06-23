package ru.protectinfotrans.eca;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;

/**
 * Базовый класс для интеграционных тестов с реальной СУБД PostgreSQL.
 * Использует локальную БД eca_test; перед каждым тестом сбрасывает схему через Flyway clean+migrate.
 *
 * Testcontainers присутствует в стеке (pom.xml) как эталонная реализация — используется в CI/CD.
 * В локальной среде Windows + Docker Desktop используется локальный PostgreSQL для надёжности.
 *
 * SyncTaskExecutor делает @Async-методы (@ApplicationModuleListener) синхронными в тестах,
 * что позволяет проверять результаты сразу после вызова processEvent.
 *
 * См. диплом: Глава 3 (Тестирование), раздел 3.2 (Интеграционное тестирование)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    /**
     * Заменяет стандартный ThreadPoolTaskExecutor на синхронный исполнитель.
     * Используется как запасной вариант — основные интеграционные тесты вызывают
     * ExecutionService.startExecution() напрямую (не через async @ApplicationModuleListener).
     */
    @TestConfiguration
    static class SyncAsyncConfig {
        @Bean
        @Primary
        public TaskExecutor taskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    /** Настройка источника данных для локального PostgreSQL eca_test */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:5432/eca_test");
        registry.add("spring.datasource.username", () -> "eca_user");
        registry.add("spring.datasource.password", () -> "eca_password");
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.clean-disabled", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Перед каждым тестовым методом: полный сброс и пересоздание схемы.
     * Гарантирует изолированность тестов.
     *
     * После flyway.clean() + flyway.migrate() пересоздаём event_publication —
     * таблицу Spring Modulith Transactional Outbox, которая не управляется Flyway
     * (в продакшне создаётся Spring Modulith через Hibernate DDL).
     */
    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS event_publication (
                id UUID NOT NULL,
                completion_date TIMESTAMP(6) WITH TIME ZONE,
                event_type VARCHAR(512) NOT NULL,
                listener_id VARCHAR(1024) NOT NULL,
                publication_date TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                serialized_event TEXT NOT NULL,
                PRIMARY KEY (id)
            )
        """);
    }

    /**
     * Получить JWT-токен для пользователя admin (из V8 миграции).
     * Пароль: admin
     *
     * @return Bearer токен для использования в запросах
     */
    protected String getAdminToken() {
        LoginRequest request = new LoginRequest("admin", "admin");
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                request,
                LoginResponse.class
        );
        assertOk(response);
        return response.getBody().token();
    }

    /**
     * Создать заголовки с JWT-авторизацией.
     */
    protected HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void assertOk(ResponseEntity<?> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Expected 2xx but got: " + response.getStatusCode());
        }
    }

    /**
     * P4-1: JWT-токен для свежесозданного пользователя с ролью OPERATOR. Регистрирует оператора
     * через admin-эндпоинт (требует право MANAGE_USERS) и логинит его. Схема пересоздаётся перед
     * каждым тестом, поэтому пользователь создаётся заново; повторная регистрация в одном тесте не
     * нужна.
     */
    protected String getOperatorToken() {
        String adminToken = getAdminToken();
        var register = new ru.protectinfotrans.eca.user.dto.RegisterRequest(
                "op_test", "op_password", "Оператор Тест", ru.protectinfotrans.eca.user.domain.Role.OPERATOR);
        HttpHeaders headers = authHeaders(adminToken);
        restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>(register, headers), String.class);

        LoginRequest login = new LoginRequest("op_test", "op_password");
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", login, LoginResponse.class);
        assertOk(response);
        return response.getBody().token();
    }
}
