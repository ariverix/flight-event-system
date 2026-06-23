package ru.protectinfotrans.eca.eventhandling.domain;

/**
 * Когда обработчик срабатывает (P3-4): на успехе шага, на неуспехе (false), или на любом исходе.
 * Соответствует чекбоксу Notify решения шага (true/false ветви) в паритете SITA.
 */
public enum NotificationTrigger {
    ON_SUCCESS,
    ON_FAILURE,
    ON_ANY;

    /** Подходит ли триггер для фактического исхода шага. */
    public boolean matches(boolean success) {
        return this == ON_ANY || (success ? this == ON_SUCCESS : this == ON_FAILURE);
    }
}
