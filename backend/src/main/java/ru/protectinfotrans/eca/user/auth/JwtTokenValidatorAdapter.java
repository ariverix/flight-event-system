package ru.protectinfotrans.eca.user.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.TokenValidatorPort;
import ru.protectinfotrans.eca.user.application.JwtService;

/**
 * Адаптер {@link TokenValidatorPort} → {@link JwtService} для WS-аутентификации (P7-4).
 *
 * <p>Находится в пакете {@code user.auth} (named interface) чтобы оставаться
 * в рамках публичного API модуля {@code user} и быть видимым для других модулей
 * через root-уровневый {@link TokenValidatorPort}.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenValidatorAdapter implements TokenValidatorPort {

    private final JwtService jwtService;

    @Override
    public boolean isValid(String token) {
        return jwtService.isTokenValid(token);
    }
}
