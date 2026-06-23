package ru.protectinfotrans.eca.eventhandling.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.protectinfotrans.eca.eventhandling.domain.Folder;
import ru.protectinfotrans.eca.eventhandling.dto.FolderCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.FolderResponse;
import ru.protectinfotrans.eca.eventhandling.port.in.FolderManagementUseCase;
import ru.protectinfotrans.eca.eventhandling.port.out.FolderRepositoryPort;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class FolderService implements FolderManagementUseCase {

    private final FolderRepositoryPort folderRepository;

    @Override
    public FolderResponse createFolder(FolderCreateRequest request) {
        if (request.parentId() != null && folderRepository.findById(request.parentId()).isEmpty()) {
            throw new IllegalArgumentException("Родительская папка не найдена: " + request.parentId());
        }
        Folder saved = folderRepository.save(Folder.builder()
                .name(request.name())
                .parentId(request.parentId())
                .build());
        log.info("Создана папка id={} name='{}' parentId={}", saved.getId(), saved.getName(), saved.getParentId());
        return FolderResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FolderResponse> listFolders() {
        return folderRepository.findAll().stream().map(FolderResponse::from).toList();
    }
}
