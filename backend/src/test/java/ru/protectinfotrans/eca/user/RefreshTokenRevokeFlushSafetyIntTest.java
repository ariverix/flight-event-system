package ru.protectinfotrans.eca.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.protectinfotrans.eca.BaseIntegrationTest;
import ru.protectinfotrans.eca.user.adapter.out.UserJpaRepository;
import ru.protectinfotrans.eca.user.domain.Role;
import ru.protectinfotrans.eca.user.domain.User;
import ru.protectinfotrans.eca.user.port.out.RefreshTokenRepositoryPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Follow-up к ревью P4-6: {@code revokeAllActiveForUser} помечен
 * {@code @Modifying(clearAutomatically = true, flushAutomatically = true)} — системная защита
 * от потери НЕСОХРАНЁННЫХ изменений managed-сущностей ДРУГИХ таблиц. Hibernate в режиме
 * {@code FlushMode.AUTO} сам флашит контекст перед JPQL-запросом, но только если таблица
 * запроса пересекается с таблицами грязных сущностей — для {@code users} (грязная сущность)
 * vs {@code refresh_tokens} (таблица запроса) это разные таблицы, "родной" auto-flush Hibernate
 * НЕ спас бы. Явный {@code flushAutomatically = true} на самом запросе — безусловный flush,
 * закрывающий именно этот кросс-табличный случай.
 *
 * <p>Тест ниже проверяет ИМЕННО эту системную защиту в изоляции от
 * {@code UserService.changePassword} (который дополнительно страхуется явным
 * {@code saveAndFlush} на вызывающей стороне) — мутирует {@link User} через обычный
 * {@code save()} (НЕ {@code saveAndFlush}) и сразу вызывает {@code revokeAllActiveForUser}
 * в той же транзакции. Без {@code flushAutomatically = true} этот тест красный (проверено при
 * ревью P4-6-follow-up: временный откат аннотации воспроизвёл потерю изменения 1:1).
 */
@DisplayName("RefreshTokenRepositoryPort.revokeAllActiveForUser: flushAutomatically не теряет несохранённые изменения других таблиц")
class RefreshTokenRevokeFlushSafetyIntTest extends BaseIntegrationTest {

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private RefreshTokenRepositoryPort refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("save() (без saveAndFlush) на User переживает revokeAllActiveForUser в той же транзакции")
    void unflushedUserChangeSurvivesRevokeAllActiveForUser() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Long userId = tx.execute(status -> userRepository.save(User.builder()
                .username("flush_probe")
                .passwordHash(passwordEncoder.encode("initial"))
                .fullName("До изменения")
                .role(Role.OPERATOR)
                .enabled(true)
                .build()).getId());

        tx.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setFullName("После изменения (несохранённое до save())");
            userRepository.save(user); // НЕ saveAndFlush — намеренно: эмулируем вызывающего без явного flush

            refreshTokenRepository.revokeAllActiveForUser(userId); // другая таблица (refresh_tokens)
        });

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("После изменения (несохранённое до save())");
    }
}
