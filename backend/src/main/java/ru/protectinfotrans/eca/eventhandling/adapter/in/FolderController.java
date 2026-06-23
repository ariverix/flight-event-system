package ru.protectinfotrans.eca.eventhandling.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.protectinfotrans.eca.eventhandling.dto.FolderCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.FolderResponse;
import ru.protectinfotrans.eca.eventhandling.port.in.FolderManagementUseCase;

import java.util.List;

@Tag(name = "Folders", description = "Папки последовательностей (P3-4)")
@RestController
@RequestMapping("/api/v1/folders")
@RequiredArgsConstructor
public class FolderController {

    private final FolderManagementUseCase folderUseCase;

    @Operation(summary = "Создать папку")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public FolderResponse create(@Valid @RequestBody FolderCreateRequest request) {
        return folderUseCase.createFolder(request);
    }

    @Operation(summary = "Список папок")
    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public List<FolderResponse> list() {
        return folderUseCase.listFolders();
    }
}
