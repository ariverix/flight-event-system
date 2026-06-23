package ru.protectinfotrans.eca.eventhandling.port.in;

import ru.protectinfotrans.eca.eventhandling.dto.FolderCreateRequest;
import ru.protectinfotrans.eca.eventhandling.dto.FolderResponse;

import java.util.List;

/** CRUD папок последовательностей (P3-4). */
public interface FolderManagementUseCase {

    FolderResponse createFolder(FolderCreateRequest request);

    List<FolderResponse> listFolders();
}
