package ru.protectinfotrans.eca.eventhandling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Папка последовательностей (P3-4, паритет SITA): организует sequences в дерево; на папку вешается
 * event-handling-конфигурация, наследуемая вложенными папками и последовательностями.
 *
 * <p>{@link #parentId} — простой nullable self-FK ({@code Long}, не JPA-связь): дерево обходится
 * итеративно в {@code EventHandlerResolver} вверх до корня (parentId == null). Цикл в дереве папок
 * невозможен создать через API (родитель указывается при создании, существующая папка не
 * переподчиняется).
 */
@Entity
@Table(name = "folders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
