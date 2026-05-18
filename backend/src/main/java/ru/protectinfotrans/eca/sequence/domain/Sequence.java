package ru.protectinfotrans.eca.sequence.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "sequences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SequenceStatus status;

    /** JSONB — критерии запуска последовательности. null = запуск в начале каждого нового рейса */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "start_criteria", columnDefinition = "jsonb")
    private String startCriteriaJson;

    /** JSONB — критерии остановки. null = завершается только через END или ABORT */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stop_criteria", columnDefinition = "jsonb")
    private String stopCriteriaJson;

    @Builder.Default
    @OneToMany(mappedBy = "sequence", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<Step> steps = new ArrayList<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
