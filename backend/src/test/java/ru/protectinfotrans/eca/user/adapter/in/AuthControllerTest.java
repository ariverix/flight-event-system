package ru.protectinfotrans.eca.user.adapter.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.user.application.JwtService;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.LoginRequest;
import ru.protectinfotrans.eca.user.dto.LoginResponse;
import ru.protectinfotrans.eca.user.dto.RegisterRequest;
import ru.protectinfotrans.eca.user.dto.UserResponse;
import ru.protectinfotrans.eca.user.port.out.AuditLogPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для AuthController.
 * Вызывает методы контроллера напрямую (без Spring MVC контекста),
 * проверяет ветвление по статусам ответа.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuditLogPort auditLogPort;

    @InjectMocks
    private AuthController controller;

    private User user;

    @BeforeEach
    void setUp() {
        controller = new AuthController(userService, jwtService, auditLogPort, new ObjectMapper());

        user = User.builder()
                .id(1L)
                .username("operator1")
                .passwordHash("hashed")
                .fullName("Operator One")
                .role(Role.OPERATOR)
                .enabled(true)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("Login")
    class Login {

        @Test
        @DisplayName("должен вернуть токен при успешной аутентификации")
        void shouldLoginSuccessfully() {
            when(userService.findByUsername("operator1")).thenReturn(user);
            when(userService.checkPassword(user, "pass")).thenReturn(true);
            when(jwtService.generateToken("operator1", Role.OPERATOR)).thenReturn("jwt-token");

            ResponseEntity<?> response = controller.login(new LoginRequest("operator1", "pass"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(LoginResponse.class);
            LoginResponse body = (LoginResponse) response.getBody();
            assertThat(body.token()).isEqualTo("jwt-token");
            assertThat(body.username()).isEqualTo("operator1");
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("должен вернуть 401 если пользователь не найден")
        void shouldReturn401WhenUserNotFound() {
            when(userService.findByUsername("unknown")).thenReturn(null);

            ResponseEntity<?> response = controller.login(new LoginRequest("unknown", "pass"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(auditLogPort, never()).save(any());
        }

        @Test
        @DisplayName("должен вернуть 403 если пользователь отключён")
        void shouldReturn403WhenUserDisabled() {
            user.setEnabled(false);
            when(userService.findByUsername("operator1")).thenReturn(user);

            ResponseEntity<?> response = controller.login(new LoginRequest("operator1", "pass"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("должен вернуть 401 при неверном пароле")
        void shouldReturn401WhenWrongPassword() {
            when(userService.findByUsername("operator1")).thenReturn(user);
            when(userService.checkPassword(user, "wrong")).thenReturn(false);

            ResponseEntity<?> response = controller.login(new LoginRequest("operator1", "wrong"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(jwtService, never()).generateToken(any(), any());
        }
    }

    @Nested
    @DisplayName("Register")
    class Register {

        @Test
        @DisplayName("должен вернуть 201 при успешной регистрации")
        void shouldRegisterSuccessfully() {
            when(userService.registerUser("newuser", "pass1234", "New User", Role.OPERATOR))
                    .thenReturn(User.builder()
                            .id(2L).username("newuser").fullName("New User")
                            .role(Role.OPERATOR).enabled(true).build());

            ResponseEntity<?> response = controller.register(
                    new RegisterRequest("newuser", "pass1234", "New User", Role.OPERATOR));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isInstanceOf(UserResponse.class);
            assertThat(((UserResponse) response.getBody()).username()).isEqualTo("newuser");
        }

        @Test
        @DisplayName("должен вернуть 409 если username занят")
        void shouldReturn409WhenUsernameTaken() {
            when(userService.registerUser(any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("User with username 'newuser' already exists"));

            ResponseEntity<?> response = controller.register(
                    new RegisterRequest("newuser", "pass1234", "New User", Role.OPERATOR));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo(
                    "User with username 'newuser' already exists");
        }
    }

    @Nested
    @DisplayName("GetCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("должен вернуть текущего пользователя при наличии аутентификации")
        void shouldReturnCurrentUser() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("operator1", null, List.of()));
            when(userService.findByUsername("operator1")).thenReturn(user);

            ResponseEntity<?> response = controller.getCurrentUser();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((UserResponse) response.getBody()).username()).isEqualTo("operator1");
        }

        @Test
        @DisplayName("должен вернуть 401 если нет аутентификации")
        void shouldReturn401WhenNotAuthenticated() {
            SecurityContextHolder.clearContext();

            ResponseEntity<?> response = controller.getCurrentUser();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("должен вернуть 404 если пользователь не найден в БД")
        void shouldReturn404WhenUserMissingInDb() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("ghost", null, List.of()));
            when(userService.findByUsername("ghost")).thenReturn(null);

            ResponseEntity<?> response = controller.getCurrentUser();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
