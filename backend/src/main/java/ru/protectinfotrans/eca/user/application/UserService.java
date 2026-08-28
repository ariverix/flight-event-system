package ru.protectinfotrans.eca.user.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.LogSanitizer;
import ru.protectinfotrans.eca.user.adapter.out.UserJpaRepository;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.port.out.UserLookupPort;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserService implements UserLookupPort {

    private final UserJpaRepository userRepository;
    private final ru.protectinfotrans.eca.user.port.out.AuditLogPort auditLogPort;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final RefreshTokenService refreshTokenService;

    /** @throws IllegalArgumentException если username уже занят */
    public User registerUser(String username, String password, String fullName, Role role) {
        if (userRepository.existsByUsername(username)) {
            log.warn("Registration failed: username '{}' already exists", LogSanitizer.sanitize(username));
            throw new IllegalArgumentException("User with username '" + username + "' already exists");
        }

        String passwordHash = passwordEncoder.encode(password);

        User user = User.builder()
                .username(username)
                .passwordHash(passwordHash)
                .fullName(fullName)
                .role(role)
                .enabled(true)
                .build();

        User savedUser = userRepository.save(user);

        log.info("User registered: id={}, username={}, role={}", savedUser.getId(),
                LogSanitizer.sanitize(username), role);

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("CREATE_USER")
                .entityType("USER")
                .entityId(savedUser.getId())
                .detailsJson(toJson(Map.of("username", username, "role", role.name())))
                .build());

        return savedUser;
    }

    /**
     * Поиск пользователя по username.
     *
     * @param username логин
     * @return пользователь или null
     */
    @Transactional(readOnly = true)
    public User findByUsername(String username) {
        return userRepository.findByUsername(username).orElse(null);
    }

    /** P4-2: поиск по id (для обновления access-токена при refresh). */
    @Transactional(readOnly = true)
    public User findById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    /**
     * Проверка пароля пользователя.
     * Используется при аутентификации.
     *
     * @param user пользователь
     * @param rawPassword пароль в открытом виде
     * @return true если пароль корректен
     */
    public boolean checkPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User toggleUserEnabled(Long userId, String currentUsername) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: id=" + userId));

        if (user.getUsername().equals(currentUsername)) {
            throw new IllegalStateException("You cannot disable your own account");
        }

        user.setEnabled(!user.getEnabled());
        User updatedUser = userRepository.save(user);

        log.info("User toggled: id={}, username={}, enabled={}", userId,
                LogSanitizer.sanitize(user.getUsername()), user.getEnabled());

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("TOGGLE_USER")
                .entityType("USER")
                .entityId(userId)
                .detailsJson(toJson(Map.of("username", user.getUsername(), "enabled", user.getEnabled())))
                .build());

        return updatedUser;
    }

    /**
     * Self-service смена пароля аутентифицированным пользователем (backlog из
     * PRODUCTION_READINESS_REPORT.md, раздел "Безопасность").
     *
     * @param userId id текущего (self) пользователя
     * @param currentPassword текущий пароль (для подтверждения владения аккаунтом)
     * @param newPassword новый пароль (валидация длины — на уровне DTO)
     * @throws IllegalArgumentException пользователь не найден ИЛИ текущий пароль неверен
     *         (сообщение generic — "Invalid current password", без утечки существования юзера,
     *         хотя здесь это self-service на своём же id, так что риска раскрытия нет)
     */
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: id=" + userId));

        if (!checkPassword(user, currentPassword)) {
            log.warn("Password change failed: incorrect current password for user id={}", userId);
            throw new IllegalArgumentException("Invalid current password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // saveAndFlush, а не save: revokeAllForUser ниже бьёт по БД bulk-запросом с
        // @Modifying(clearAutomatically = true) — он detach'ит persistence context БЕЗ
        // предварительного flush. Обычный save() на уже managed-сущности (merge) не пишет
        // в БД немедленно — SQL откладывается до flush/commit. Без явного flush здесь
        // clearAutomatically очистил бы контекст ДО того, как passwordHash реально попал
        // бы в БД, и изменение пароля молча терялось бы (клиенту всё равно вернулся бы 204).
        userRepository.saveAndFlush(user);

        // в ТОЙ ЖЕ транзакции, что и смена пароля: без этого при сбое между двумя отдельными
        // commit'ами пароль уже сменился бы, а старые refresh-токены остались бы живы —
        // нарушение инварианта "смена пароля закрывает все сессии" (ревью P4-6, HIGH)
        refreshTokenService.revokeAllForUser(userId);

        log.info("Password changed for user: id={}, username={}", userId,
                LogSanitizer.sanitize(user.getUsername()));

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("USER_PASSWORD_CHANGED")
                // actor == entity: пользователь меняет свой собственный пароль
                .userId(userId)
                .entityType("USER")
                .entityId(userId)
                .build());
    }

    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    /**
     * Реализация {@link UserLookupPort} — узкий доступ к id пользователя по username
     * для других модулей (без раскрытия domain.User/полного UserService API).
     */
    @Override
    @Transactional(readOnly = true)
    public Long findUserIdByUsername(String username) {
        User user = findByUsername(username);
        return user != null ? user.getId() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit details", e);
            return null;
        }
    }
}
