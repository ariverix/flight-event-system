package ru.protectinfotrans.eca.eventhandling.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.eventhandling.domain.Folder;

interface FolderJpaRepository extends JpaRepository<Folder, Long> {
}
