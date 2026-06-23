package ru.protectinfotrans.eca.eventhandling.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.protectinfotrans.eca.eventhandling.domain.Folder;
import ru.protectinfotrans.eca.eventhandling.port.out.FolderRepositoryPort;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FolderJpaAdapter implements FolderRepositoryPort {

    private final FolderJpaRepository jpaRepository;

    @Override
    public Folder save(Folder folder) {
        return jpaRepository.save(folder);
    }

    @Override
    public Optional<Folder> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Folder> findAll() {
        return jpaRepository.findAll();
    }
}
