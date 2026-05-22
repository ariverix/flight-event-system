package ru.protectinfotrans.eca.sequence.dto;

import java.util.List;

/**
 * Универсальный ответ с пагинацией.
 */
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int number,
        int size
) {}
