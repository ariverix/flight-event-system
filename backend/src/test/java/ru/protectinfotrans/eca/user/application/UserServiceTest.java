package ru.protectinfotrans.eca.user.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Mock
    private RefreshTokenService refreshTokenService;

    // Реальный BCrypt-энкодер — тесты проверяют фактическое хеширование/сравнение пароля,
    // мок здесь не годится (нужна настоящая криптография для shouldHashPassword/checkPassword).
    // @Spy оборачивает реальный объект так, чтобы Mockito смог внедрить его через @InjectMocks.
    @Spy
    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Реальный ObjectMapper — нужен для сериализации деталей аудита в JSON.
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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
            assertThat(audit.getAction()).isEqualTo("CREATE_USER");
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

        @Test
        @DisplayName("Should find user id by username (UserLookupPort)")
        void shouldFindUserIdByUsername() {
            User user = User.builder()
                    .id(42L)
                    .username("testuser")
                    .build();
            when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

            Long result = userService.findUserIdByUsername("testuser");

            assertThat(result).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should return null id when username not found (UserLookupPort)")
        void shouldReturnNullIdWhenUsernameNotFound() {
            when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

            Long result = userService.findUserIdByUsername("unknown");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Change Password")
    class ChangePassword {

        @Test
        @DisplayName("Should change password when current password is correct")
        void shouldChangePasswordSuccessfully() {
            String oldHash = passwordEncoder.encode("oldpassword");
            User user = User.builder()
                    .id(1L)
                    .username("testuser")
                    .passwordHash(oldHash)
                    .build();

            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.changePassword(1L, "oldpassword", "newpassword");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).saveAndFlush(userCaptor.capture());
            User saved = userCaptor.getValue();
            assertThat(saved.getPasswordHash()).isNotEqualTo(oldHash);
            assertThat(passwordEncoder.matches("newpassword", saved.getPasswordHash())).isTrue();

            verify(auditLogPort).save(any(AuditLog.class));
        }

        @Test
        @DisplayName("Should revoke all refresh tokens in the same call as the password change (atomicity, review P4-6 HIGH)")
        void shouldRevokeAllRefreshTokensOnPasswordChange() {
            String oldHash = passwordEncoder.encode("oldpassword");
            User user = User.builder()
                    .id(3L)
                    .username("testuser")
                    .passwordHash(oldHash)
                    .build();

            when(userRepository.findById(3L)).thenReturn(Optional.of(user));

            userService.changePassword(3L, "oldpassword", "newpassword");

            verify(refreshTokenService).revokeAllForUser(3L);
        }

        @Test
        @DisplayName("Should create audit log entry with correct action/entity on password change")
        void shouldCreateAuditLogOnPasswordChange() {
            String oldHash = passwordEncoder.encode("oldpassword");
            User user = User.builder()
                    .id(7L)
                    .username("audituser")
                    .passwordHash(oldHash)
                    .build();

            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            userService.changePassword(7L, "oldpassword", "newpassword");

            ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogPort).save(auditCaptor.capture());

            AuditLog audit = auditCaptor.getValue();
            assertThat(audit.getAction()).isEqualTo("USER_PASSWORD_CHANGED");
            assertThat(audit.getEntityType()).isEqualTo("USER");
            assertThat(audit.getEntityId()).isEqualTo(7L);
            assertThat(audit.getUserId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("Should throw exception when user not found")
        void shouldThrowExceptionWhenUserNotFound() {
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.changePassword(999L, "old", "newpassword"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("User not found");

            verify(userRepository, never()).save(any());
            verify(auditLogPort, never()).save(any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
        }

        @Test
        @DisplayName("Should throw exception when current password is incorrect")
        void shouldThrowExceptionWhenCurrentPasswordIncorrect() {
            String hash = passwordEncoder.encode("correctpassword");
            User user = User.builder()
                    .id(2L)
                    .username("testuser")
                    .passwordHash(hash)
                    .build();

            when(userRepository.findById(2L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.changePassword(2L, "wrongpassword", "newpassword"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid current password");

            verify(userRepository, never()).save(any());
            verify(auditLogPort, never()).save(any());
            verify(refreshTokenService, never()).revokeAllForUser(any());
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
            assertThat(audit.getAction()).isEqualTo("TOGGLE_USER");
            assertThat(audit.getEntityType()).isEqualTo("USER");
            assertThat(audit.getEntityId()).isEqualTo(5L);
        }
    }
}
