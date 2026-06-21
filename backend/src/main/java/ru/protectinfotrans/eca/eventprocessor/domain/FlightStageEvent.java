package ru.protectinfotrans.eca.eventprocessor.domain;

import jakarta.persistence.*;
import lombok.*;
import ru.protectinfotrans.eca.FlightStage;

import java.time.LocalDateTime;

/**
 * Факт смены стадии полёта (OOOI: Out/Off/On/In, плюс Init/Summary) по борту — V29.
 *
 * <p>Источник истины для Off-таймстампа, который POSITION-критерий ({@code CriterionEvaluator})
 * использует как точку отсчёта для "position not reported in last {x} min" — паритет с SITA
 * Sequencer (см. javadoc V29). Записывается на каждую смену стадии независимо от того, пришла
 * ли она через {@code notifyFlightStageChange} (системное уведомление) или была разобрана из
 * OOOI-меток входящего ACARS-сообщения (ARINC 618).
 */
@Entity
@Table(name = "flight_stage_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlightStageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String aircraftId;
    private String flightNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStage stage;

    private LocalDateTime occurredAt;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
