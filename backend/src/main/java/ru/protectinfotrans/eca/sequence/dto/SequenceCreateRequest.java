package ru.protectinfotrans.eca.sequence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Запрос на создание последовательности.
 * См. диплом: раздел 1.3.5 (UC-01 Создать последовательность)
 */
public record SequenceCreateRequest(

        @NotBlank(message = "Наименование обязательно")
        @Size(max = 100, message = "Наименование не более 100 символов")
        String name,

        @Size(max = 500, message = "Описание не более 500 символов")
        String description,

        String startCriteriaJson,

        String stopCriteriaJson
) {}
