package ru.protectinfotrans.eca.eventhandling.domain;

/**
 * Уровень привязки обработчика событий (P3-4, паритет SITA Event Handling):
 * {@code FOLDER} — конфигурация наследуется вложенными папками/последовательностями;
 * {@code SEQUENCE} — переопределяет унаследованную конфигурацию для конкретной последовательности.
 */
public enum HandlerScope {
    FOLDER,
    SEQUENCE
}
