package ru.protectinfotrans.eca.user.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.user.domain.RefreshToken;
import ru.protectinfotrans.eca.user.port.out.RefreshTokenRepositoryPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Управление refresh-токенами (P4-2): выдача, ротация при обновлении, инвалидизация, обнаружение
 * повторного использования (reuse) украденного/уже ротированного токена.
 *
 * <p><b>Безопасность:</b> наружу отдаётся высокоэнтропийный (256 бит) opaque-токен; в БД хранится
 * только его SHA-256-хэш. Ротация — каждый refresh ИНВАЛИДИРУЕТ предъявленный токен и выдаёт новый.
 * Reuse-detection — если предъявлен УЖЕ отозванный токен (классический признак кражи цепочки),
 * отзываются ВСЕ активные токены пользователя (см. ADR-0003).
 */
@Service
@Slf4j
@Transactional
public class RefreshTokenService {

    private final RefreshTokenRepositoryPort repository;
    private final long refreshExpirationMs;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepositoryPort repository,
                               @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.repository = repository;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /** Результат ротации: новый refresh-токен (plaintext) и владелец. */
    public record Rotation(String refreshToken, Long userId) {}

    /** Выдать новый refresh-токен пользователю; возвращает PLAINTEXT (хэш сохраняется в БД). */
    public String issue(Long userId) {
        String plaintext = generateToken();
        repository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(hash(plaintext))
                .expiresAt(LocalDateTime.now().plus(java.time.Duration.ofMillis(refreshExpirationMs)))
                .revoked(false)
                .build());
        return plaintext;
    }

    /**
     * Ротация: валидирует предъявленный токен, отзывает его и выдаёт новый. Reuse уже отозванного
     * токена → отзыв всех активных токенов пользователя и {@link InvalidRefreshTokenException}.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String presentedToken) {
        RefreshToken existing = repository.findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token не найден"));

        if (existing.isRevoked()) {
            // Предъявлен уже ротированный/отозванный токен — вероятная кража цепочки.
            int revoked = repository.revokeAllActiveForUser(existing.getUserId());
            log.warn("Reuse отозванного refresh-токена (userId={}) — отозвано активных токенов: {}",
                    existing.getUserId(), revoked);
            throw new InvalidRefreshTokenException("Refresh token отозван");
        }
        if (existing.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidRefreshTokenException("Refresh token истёк");
        }

        existing.setRevoked(true);
        repository.save(existing);

        String newPlaintext = issue(existing.getUserId());
        log.debug("Refresh-токен ротирован для userId={}", existing.getUserId());
        return new Rotation(newPlaintext, existing.getUserId());
    }

    /**
     * Отзыв токена (logout). Идемпотентно: неизвестный токен — no-op.
     * @return userId владельца, если токен найден; {@code null} иначе (для аудита).
     */
    public Long revoke(String presentedToken) {
        return repository.findByTokenHash(hash(presentedToken)).map(t -> {
            t.setRevoked(true);
            repository.save(t);
            return t.getUserId();
        }).orElse(null);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
