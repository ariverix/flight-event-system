package ru.protectinfotrans.eca.user.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Пользователь системы — оператор или администратор.
 *
 * См. диплом: раздел 1.3.4 (ключевые сущности), раздел 1.3.5 (акторы)
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    private String fullName;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Builder.Default
    private Boolean enabled = true;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
