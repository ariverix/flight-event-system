package ru.protectinfotrans.eca.user.application;

/** Refresh-токен не найден / отозван / истёк (P4-2). Маппится в 401. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
