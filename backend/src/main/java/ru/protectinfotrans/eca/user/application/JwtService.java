package ru.protectinfotrans.eca.user.application;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.protectinfotrans.eca.user.domain.Role;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

// Время жизни токена настраивается через app.jwt.expiration-ms в application.yml
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secretKey;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    public String generateToken(String username, Role role) {
        Instant now = Instant.now();
        Instant expiration = now.plusMillis(expirationMs);

        String token = Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(getSigningKey())
                .compact();

        log.debug("Generated JWT token for user={}, role={}, expires at {}", username, role, expiration);
        return token;
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public Role extractRole(String token) {
        String roleName = extractClaims(token).get("role", String.class);
        return Role.valueOf(roleName);
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractClaims(token);
            Date expiration = claims.getExpiration();
            boolean notExpired = expiration.after(new Date());

            if (!notExpired) {
                log.warn("JWT token expired at {}", expiration);
            }

            return notExpired;
        } catch (Exception e) {
            log.error("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
