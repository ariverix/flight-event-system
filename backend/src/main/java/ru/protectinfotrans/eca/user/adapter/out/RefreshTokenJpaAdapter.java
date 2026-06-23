package ru.protectinfotrans.eca.user.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.user.domain.RefreshToken;
import ru.protectinfotrans.eca.user.port.out.RefreshTokenRepositoryPort;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RefreshTokenJpaAdapter implements RefreshTokenRepositoryPort {

    private final RefreshTokenJpaRepository jpaRepository;

    @Override
    public RefreshToken save(RefreshToken token) {
        return jpaRepository.save(token);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash);
    }

    @Override
    public int revokeAllActiveForUser(Long userId) {
        return jpaRepository.revokeAllActiveForUser(userId);
    }
}
