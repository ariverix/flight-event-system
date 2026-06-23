package ru.protectinfotrans.eca.eventhandling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Запрос на создание папки (P3-4). parentId == null — корневая папка. */
public record FolderCreateRequest(

        @NotBlank(message = "Имя папки обязательно")
        @Size(max = 255, message = "Имя папки не более 255 символов")
        String name,

        Long parentId
) {}
