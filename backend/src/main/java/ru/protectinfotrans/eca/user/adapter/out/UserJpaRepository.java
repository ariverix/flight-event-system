package ru.protectinfotrans.eca.user.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.user.domain.User;

import java.util.Optional;

/**
 * JPA-репозиторий для сущности User.
 *
 */
public interface UserJpaRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
