package ru.protectinfotrans.eca.user.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.protectinfotrans.eca.user.domain.RefreshToken;

import java.util.Optional;

interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * {@code flushAutomatically = true}: без него {@code clearAutomatically} detach'ит
     * persistence context ДО flush любых несохранённых изменений managed-сущностей — такое
     * изменение молча теряется (найдено в ревью P4-6: {@code UserService.changePassword}
     * страховался явным {@code saveAndFlush} на вызывающей стороне; здесь та же защита ставится
     * на сам запрос — системно, для ЛЮБОГО будущего вызывающего кода с несохранёнными
     * изменениями в той же транзакции, не только для смены пароля).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.userId = :userId AND t.revoked = false")
    int revokeAllActiveForUser(@Param("userId") Long userId);
}
