package ru.protectinfotrans.eca.sequence.dto;

import java.util.List;

/**
 * Универсальный ответ с пагинацией.
 * См. диплом: раздел 1.4.3 (поток пользовательских операций)
 */
public record PageResponse<T>(
        List<T> content,
        long totalElements,
        int totalPages,
        int currentPage,
        int pageSize
) {}
