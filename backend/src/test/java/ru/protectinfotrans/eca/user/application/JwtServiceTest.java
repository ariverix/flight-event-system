package ru.protectinfotrans.eca.user.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.protectinfotrans.eca.user.domain.Role;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit-тесты для JwtService.
 */
class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        // Устанавливаем тестовые значения через рефлексию (т.к. @Value не работает в unit-тестах)
        ReflectionTestUtils.setField(jwtService, "secretKey",
                "test-jwt-secret-key-for-testing-minimum-256-bits-long-secure");
        ReflectionTestUtils.setField(jwtService, "expirationMs", 86400000L); // 24 hours
    }

    @Nested
    @DisplayName("Token Generation")
    class TokenGeneration {

        @Test
        @DisplayName("Should generate valid JWT token for OPERATOR")
        void shouldGenerateTokenForOperator() {
            String token = jwtService.generateToken("operator1", Role.OPERATOR);

            assertThat(token).isNotNull().isNotEmpty();
            assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts: header.payload.signature
        }

        @Test
        @DisplayName("Should generate valid JWT token for ADMIN")
        void shouldGenerateTokenForAdmin() {
            String token = jwtService.generateToken("admin", Role.ADMIN);

            assertThat(token).isNotNull().isNotEmpty();
        }

        @Test
        @DisplayName("Should generate different tokens for different users")
        void shouldGenerateDifferentTokens() {
            String token1 = jwtService.generateToken("user1", Role.OPERATOR);
            String token2 = jwtService.generateToken("user2", Role.OPERATOR);

            assertThat(token1).isNotEqualTo(token2);
        }
    }

    @Nested
    @DisplayName("Token Extraction")
    class TokenExtraction {

        @Test
        @DisplayName("Should extract username from token")
        void shouldExtractUsername() {
            String username = "testuser";
            String token = jwtService.generateToken(username, Role.OPERATOR);

            String extractedUsername = jwtService.extractUsername(token);

            assertThat(extractedUsername).isEqualTo(username);
        }

        @Test
        @DisplayName("Should extract role from token")
        void shouldExtractRole() {
            String token = jwtService.generateToken("admin", Role.ADMIN);

            Role extractedRole = jwtService.extractRole(token);

            assertThat(extractedRole).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("Should extract OPERATOR role correctly")
        void shouldExtractOperatorRole() {
            String token = jwtService.generateToken("operator", Role.OPERATOR);

            Role extractedRole = jwtService.extractRole(token);

            assertThat(extractedRole).isEqualTo(Role.OPERATOR);
        }
    }

    @Nested
    @DisplayName("Token Validation")
    class TokenValidation {

        @Test
        @DisplayName("Should validate valid token")
        void shouldValidateValidToken() {
            String token = jwtService.generateToken("user", Role.OPERATOR);

            boolean isValid = jwtService.isTokenValid(token);

            assertThat(isValid).isTrue();
        }

        @Test
        @DisplayName("Should reject expired token")
        void shouldRejectExpiredToken() {
            // Создаем сервис с коротким временем жизни токена
            JwtService shortLivedService = new JwtService();
            ReflectionTestUtils.setField(shortLivedService, "secretKey",
                    "test-jwt-secret-key-for-testing-minimum-256-bits-long-secure");
            ReflectionTestUtils.setField(shortLivedService, "expirationMs", -1000L); // Уже истёк

            String token = shortLivedService.generateToken("user", Role.OPERATOR);

            boolean isValid = shortLivedService.isTokenValid(token);

            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("Should reject invalid token")
        void shouldRejectInvalidToken() {
            String invalidToken = "invalid.jwt.token";

            boolean isValid = jwtService.isTokenValid(invalidToken);

            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("Should reject token with wrong signature")
        void shouldRejectTokenWithWrongSignature() {
            String token = jwtService.generateToken("user", Role.OPERATOR);

            // Создаем другой сервис с другим секретом
            JwtService otherService = new JwtService();
            ReflectionTestUtils.setField(otherService, "secretKey",
                    "different-secret-key-for-testing-minimum-256-bits-long");
            ReflectionTestUtils.setField(otherService, "expirationMs", 86400000L);

            boolean isValid = otherService.isTokenValid(token);

            assertThat(isValid).isFalse();
        }
    }

    @Nested
    @DisplayName("End-to-End Token Flow")
    class EndToEndFlow {

        @Test
        @DisplayName("Should generate, extract and validate token correctly")
        void shouldHandleCompleteTokenFlow() {
            String username = "testuser";
            Role role = Role.ADMIN;

            // Generate
            String token = jwtService.generateToken(username, role);

            // Validate
            assertThat(jwtService.isTokenValid(token)).isTrue();

            // Extract
            assertThat(jwtService.extractUsername(token)).isEqualTo(username);
            assertThat(jwtService.extractRole(token)).isEqualTo(role);
        }
    }
}
