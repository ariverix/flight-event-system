package ru.protectinfotrans.eca.eventhandling.port.out;

import ru.protectinfotrans.eca.eventhandling.domain.Folder;

import java.util.List;
import java.util.Optional;

public interface FolderRepositoryPort {

    Folder save(Folder folder);

    Optional<Folder> findById(Long id);

    List<Folder> findAll();
}
