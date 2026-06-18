package ru.protectinfotrans.eca.user.adapter.in;

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
import org.springframework.security.core.Authentication;
import ru.protectinfotrans.eca.user.application.UserService;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.dto.UserResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для UserController (UC-09: управление пользователями).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserController")
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private UserController controller;

    @Nested
    @DisplayName("Список пользователей")
    class ListUsers {

        @Test
        @DisplayName("должен вернуть список всех пользователей")
        void shouldReturnAllUsers() {
            User u1 = User.builder().id(1L).username("admin").role(Role.ADMIN).enabled(true).build();
            User u2 = User.builder().id(2L).username("op1").role(Role.OPERATOR).enabled(true).build();
            when(userService.getAllUsers()).thenReturn(List.of(u1, u2));

            ResponseEntity<List<UserResponse>> response = controller.listUsers();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody()).extracting(UserResponse::username)
                    .containsExactly("admin", "op1");
        }

        @Test
        @DisplayName("должен вернуть пустой список если пользователей нет")
        void shouldReturnEmptyList() {
            when(userService.getAllUsers()).thenReturn(List.of());

            ResponseEntity<List<UserResponse>> response = controller.listUsers();

            assertThat(response.getBody()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Переключение статуса пользователя")
    class ToggleUser {

        @BeforeEach
        void setUp() {
            when(authentication.getName()).thenReturn("admin");
        }

        @Test
        @DisplayName("должен переключить статус и вернуть обновлённого пользователя")
        void shouldToggleUserSuccessfully() {
            User toggled = User.builder().id(5L).username("op1").role(Role.OPERATOR).enabled(false).build();
            when(userService.toggleUserEnabled(5L, "admin")).thenReturn(toggled);

            ResponseEntity<?> response = controller.toggleUser(5L, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((UserResponse) response.getBody()).enabled()).isFalse();
        }

        @Test
        @DisplayName("должен вернуть 404 если пользователь не найден")
        void shouldReturn404WhenUserNotFound() {
            when(userService.toggleUserEnabled(999L, "admin"))
                    .thenThrow(new IllegalArgumentException("User not found: id=999"));

            ResponseEntity<?> response = controller.toggleUser(999L, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("User not found: id=999");
        }

        @Test
        @DisplayName("должен вернуть 400 если админ пытается отключить себя")
        void shouldReturn400WhenTogglingOwnAccount() {
            when(userService.toggleUserEnabled(1L, "admin"))
                    .thenThrow(new IllegalStateException("You cannot disable your own account"));

            ResponseEntity<?> response = controller.toggleUser(1L, authentication);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
