package ru.protectinfotrans.eca.user.port.out;

import ru.protectinfotrans.eca.user.domain.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepositoryPort {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Отозвать все активные токены пользователя (logout-all / реакция на reuse украденного токена). */
    int revokeAllActiveForUser(Long userId);
}
