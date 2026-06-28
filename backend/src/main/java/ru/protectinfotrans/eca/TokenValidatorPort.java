package ru.protectinfotrans.eca;

/**
 * Порт проверки токена аутентификации (root module — доступен всем модулям).
 *
 * <p>Используется WS-адаптером модуля {@code execution} для валидации JWT,
 * полученного в первом сообщении после WS-соединения (ADR-0005 п.4).
 * Реализуется в модуле {@code user} через {@code JwtTokenValidatorAdapter}.
 */
public interface TokenValidatorPort {

    /**
     * Проверяет, является ли токен валидным и не истёкшим.
     *
     * @param token JWT access-токен
     * @return {@code true} если токен валиден, {@code false} иначе
     */
    boolean isValid(String token);
}
