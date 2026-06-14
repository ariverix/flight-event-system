package ru.protectinfotrans.eca.user.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.user.adapter.out.UserJpaRepository;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.port.out.AuditLogPort;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для UserService.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserJpaRepository userRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @InjectMocks
    private UserService userService;

    @Nested
    @DisplayName("User Registration")
    class UserRegistration {

        @Test
        @DisplayName("Should register new user successfully")
        void shouldRegisterUser() {
            when(userRepository.existsByUsername("newuser")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            User result = userService.registerUser("newuser", "password123", "New User", Role.OPERATOR);

            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo("newuser");
            assertThat(result.getFullName()).isEqualTo("New User");
            assertThat(result.getRole()).isEqualTo(Role.OPERATOR);
            assertThat(result.getEnabled()).isTrue();
            assertThat(result.getPasswordHash()).isNotEqualTo("password123"); // Password should be hashed

            verify(userRepository).save(any(User.class));
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Should throw exception when username already exists")
        void shouldThrowExceptionWhenUsernameExists() {
            when(userRepository.existsByUsername("existing")).thenReturn(true);

            assertThatThrownBy(() ->
                    userService.registerUser("existing", "password", "User", Role.OPERATOR)
            )
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");

            verify(userRepository, never()).save(any());
            verify(auditLogPort, never()).save(any());
        }

        @Test
        @DisplayName("Should hash password during registration")
        void shouldHashPassword() {
            String rawPassword = "mypassword";
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.registerUser("user", rawPassword, "User", Role.ADMIN);

            assertThat(result.getPasswordHash())
                    .isNotEqualTo(rawPassword)
                    .startsWith("$2a$"); // BCrypt hash prefix

            verify(userRepository).save(any(User.class));
        }

        @Test
        @DisplayName("Should create audit log entry on registration")
        void shouldCreateAuditLogOnRegistration() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(123L);
                return user;
            });

            userService.registerUser("testuser", "password", "Test User", Role.OPERATOR);

            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogPort).save(auditCaptor.capture());

            AuditLog audit = auditCaptor.getValue();
            assertThat(audit.getAction()).isEqualTo("USER_REGISTERED");
            assertThat(audit.getEntityType()).isEqualTo("USER");
            assertThat(audit.getEntityId()).isEqualTo(123L);
        }
    }

    @Nested
    @DisplayName("Password Check")
    class PasswordCheck {

        @Test
        @DisplayName("Should return true for correct password")
        void shouldReturnTrueForCorrectPassword() {
            // Регистрируем пользователя с известным паролем
            when(userRepository.existsByUsername("testuser")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            User registeredUser = userService.registerUser("testuser", "mypassword", "Test", Role.OPERATOR);

            // Проверяем правильный пароль
            boolean result = userService.checkPassword(registeredUser, "mypassword");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("Should return false for incorrect password")
        void shouldReturnFalseForIncorrectPassword() {
            // Регистрируем пользователя
            when(userRepository.existsByUsername("testuser")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User user = invocation.getArgument(0);
                user.setId(1L);
                return user;
            });

            User registeredUser = userService.registerUser("testuser", "correctpassword", "Test", Role.OPERATOR);

            // Проверяем неправильный пароль
            boolean result = userService.checkPassword(registeredUser, "wrongpassword");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("User Queries")
    class UserQueries {

        @Test
        @DisplayName("Should find user by username")
        void shouldFindUserByUsername() {
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .build();
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            User result = userService.findByUsername("testuser");

            assertThat(result).isNotNull();
            assertThat(result.getUsername()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("Should return null when user not found")
        void shouldReturnNullWhenUserNotFound() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            User result = userService.findByUsername("unknown");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should return all users")
        void shouldReturnAllUsers() {
            List<User> users = List.of(
                    User.builder().id(1L).username("user1").build(),
                    User.builder().id(2L).username("user2").build()
            );
            when(userRepository.findAll()).thenReturn(users);

            List<User> result = userService.getAllUsers();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(User::getUsername)
                    .containsExactly("user1", "user2");
        }

        @Test
        @DisplayName("Should check if username exists")
        void shouldCheckUsernameExists() {
            when(userRepository.existsByUsername("existing")).thenReturn(true);
            when(userRepository.existsByUsername("nonexisting")).thenReturn(false);

            assertThat(userService.existsByUsername("existing")).isTrue();
            assertThat(userService.existsByUsername("nonexisting")).isFalse();
        }
    }

    @Nested
    @DisplayName("Toggle User Enabled")
    class ToggleUserEnabled {

        @Test
        @DisplayName("Should toggle user from enabled to disabled")
        void shouldToggleFromEnabledToDisabled() {
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .enabled(true)
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.toggleUserEnabled(1L, "admin");

            assertThat(result.getEnabled()).isFalse();
            verify(userRepository).save(user);
            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Should toggle user from disabled to enabled")
        void shouldToggleFromDisabledToEnabled() {
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .enabled(false)
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User result = userService.toggleUserEnabled(1L, "admin");

            assertThat(result.getEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should throw exception when user not found")
        void shouldThrowExceptionWhenUserNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.toggleUserEnabled(999L, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found");

            verify(userRepository, never()).save(any());
            verify(auditLogPort, never()).save(any());
        }

        @Test
        @DisplayName("Should throw exception when admin tries to disable own account")
        void shouldThrowExceptionWhenTogglingOwnAccount() {
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .enabled(true)
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.toggleUserEnabled(1L, "testuser"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot disable your own account");

            verify(userRepository, never()).save(any());
            verify(auditLogPort, never()).save(any());
        }

        @Test
        @DisplayName("Should create audit log on toggle")
        void shouldCreateAuditLogOnToggle() {
            User user = User.builder()
                    .id(5L)
                    .username("toggleuser")
                    .enabled(true)
                    .build();

            when(userRepository.findById(5L)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.toggleUserEnabled(5L, "admin");

            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogPort).save(auditCaptor.capture());

            AuditLog audit = auditCaptor.getValue();
            assertThat(audit.getAction()).isEqualTo("USER_TOGGLED");
            assertThat(audit.getEntityType()).isEqualTo("USER");
            assertThat(audit.getEntityId()).isEqualTo(5L);
        }
    }
}
