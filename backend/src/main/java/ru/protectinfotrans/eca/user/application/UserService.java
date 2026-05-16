package ru.protectinfotrans.eca.user.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.AuditLog;
import ru.protectinfotrans.eca.user.adapter.out.UserJpaRepository;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;

import java.util.List;
import java.util.Map;

/**
 * Сервис управления пользователями.
 * Реализует UC-09 (Управлять пользователями).
 *
 * Обязанности:
 * - Регистрация новых пользователей (только ADMIN)
 * - Проверка пароля при аутентификации
 * - Получение списка пользователей
 * - Включение/отключение пользователей
 *
 * См. диплом: раздел 1.3.5 (UC-09), раздел 1.3.5 (акторы)
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class UserService {

    private final UserJpaRepository userRepository;
    private final ru.protectinfotrans.eca.user.port.out.AuditLogPort auditLogPort;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    /**
     * UC-09: Регистрация нового пользователя.
     * Доступно только для администраторов.
     *
     * @param username логин
     * @param password пароль (будет хеширован)
     * @param fullName полное имя
     * @param role роль (OPERATOR или ADMIN)
     * @return созданный пользователь
     * @throws IllegalArgumentException если пользователь уже существует
     */
    public User registerUser(String username, String password, String fullName, Role role) {
        if (userRepository.existsByUsername(username)) {
            log.warn("Registration failed: username '{}' already exists", username);
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

        log.info("User registered: id={}, username={}, role={}", savedUser.getId(), username, role);

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

    /**
     * UC-09: Получить список всех пользователей.
     * Доступно только для администраторов.
     */
    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * UC-09: Включить/отключить пользователя.
     * Доступно только для администраторов.
     *
     * @param userId ID пользователя
     * @return обновлённый пользователь
     * @throws IllegalArgumentException если пользователь не найден
     */
    public User toggleUserEnabled(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: id=" + userId));

        user.setEnabled(!user.getEnabled());
        User updatedUser = userRepository.save(user);

        log.info("User toggled: id={}, username={}, enabled={}", userId, user.getUsername(), user.getEnabled());

        auditLogPort.save(ru.protectinfotrans.eca.AuditLog.builder()
                .action("TOGGLE_USER")
                .entityType("USER")
                .entityId(userId)
                .detailsJson(toJson(Map.of("username", user.getUsername(), "enabled", user.getEnabled())))
                .build());

        return updatedUser;
    }

    /**
     * Проверка существования пользователя с заданным username.
     */
    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
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
