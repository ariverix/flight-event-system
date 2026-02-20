package ru.protectinfotrans.eca.user.adapter.out;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.protectinfotrans.eca.user.domain.User;

import java.util.Optional;

/**
 * JPA-репозиторий для сущности User.
 *
 * См. диплом: раздел 1.4.1 (гексагональная архитектура - driven adapter)
 */
public interface UserJpaRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);
}
