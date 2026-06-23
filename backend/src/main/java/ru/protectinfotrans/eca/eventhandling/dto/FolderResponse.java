package ru.protectinfotrans.eca.eventhandling.dto;

import ru.protectinfotrans.eca.eventhandling.domain.Folder;

import java.time.LocalDateTime;

public record FolderResponse(
        Long id,
        String name,
        Long parentId,
        LocalDateTime createdAt
) {
    public static FolderResponse from(Folder folder) {
        return new FolderResponse(folder.getId(), folder.getName(), folder.getParentId(), folder.getCreatedAt());
    }
}
